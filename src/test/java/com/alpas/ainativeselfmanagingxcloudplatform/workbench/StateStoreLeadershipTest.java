package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class StateStoreLeadershipTest {
    @TempDir Path directory;
    private final tools.jackson.databind.json.JsonMapper json =
            new WorkbenchConfiguration().jsonMapper();

    @Test
    void epochsIncreaseAcrossRestartAndConcurrentReplacementFencesReadsAndWrites() {
        long original;
        try (var first = new StateStore(json, directory.toString())) {
            original = first.leaderEpoch();
            assertEquals(1, original);
            first.put("test", "receipt", "committed");
            try (var replacement = new StateStore(json, directory.toString())) {
                assertEquals(original + 1, replacement.leaderEpoch());
                assertUnavailable(() -> first.all("test", String.class));
                assertUnavailable(() -> first.put("test", "stale", "bad"));
                assertUnavailable(() -> first.remove("test", "receipt"));
                assertUnavailable(() -> first.requireEpoch(original));
                assertUnavailable(first::leaderEpoch);
                assertEquals(List.of("committed"), replacement.all("test", String.class));
            }
        }
        try (var restarted = new StateStore(json, directory.toString())) {
            assertEquals(original + 2, restarted.leaderEpoch());
            assertEquals(List.of("committed"), restarted.all("test", String.class));
        }
    }

    @Test
    void upgradesLegacyUuidOwnerWithoutEpochAndKeepsItsState() throws Exception {
        // A pre-epoch release persisted only this raw UUID row (not a JSON string).
        try (var store = new StateStore(json, directory.toString())) {
            store.put("test", "receipt", "legacy");
        }
        try (var connection = raw(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE workbench_leadership");
            statement.executeUpdate("DELETE FROM workbench_state WHERE bucket='coordinator' AND item_id IN ('epoch','epoch-format')");
            statement.executeUpdate("UPDATE workbench_state SET payload='00000000-0000-0000-0000-000000000001' WHERE bucket='coordinator' AND item_id='owner'");
        }
        try (var upgraded = new StateStore(json, directory.toString())) {
            assertEquals(1, upgraded.leaderEpoch());
            assertEquals(List.of("legacy"), upgraded.all("test", String.class));
        }
        try (var restarted = new StateStore(json, directory.toString())) {
            assertEquals(2, restarted.leaderEpoch());
        }
    }

    @Test
    void upgradesBothEpochOnlyAndLeaseOnlyReleasesWithoutResettingFences() throws Exception {
        try (var store = new StateStore(json, directory.toString())) {
            store.put("test", "receipt", "preserved");
        }
        try (var connection = raw(); var statement = connection.createStatement()) {
            // PR19 before integration had epoch metadata and no lease table.
            statement.executeUpdate("DROP TABLE workbench_leadership");
            statement.executeUpdate("UPDATE workbench_state SET payload='9' WHERE bucket='coordinator' AND item_id='epoch'");
        }
        try (var upgraded = new StateStore(json, directory.toString())) {
            assertEquals(10, upgraded.leaderEpoch());
            assertEquals(List.of("preserved"), upgraded.all("test", String.class));
        }
        try (var connection = raw(); var statement = connection.createStatement()) {
            // PR18 persisted epochs in the lease table, with only a legacy owner row.
            statement.executeUpdate("DELETE FROM workbench_state WHERE bucket='coordinator' AND item_id IN ('epoch','epoch-format')");
            statement.executeUpdate("UPDATE workbench_leadership SET leader_epoch=20");
        }
        try (var upgraded = new StateStore(json, directory.toString())) {
            assertEquals(21, upgraded.leaderEpoch());
            assertEquals(List.of("preserved"), upgraded.all("test", String.class));
        }
    }

    @Test
    void transactionCommitsComparedRowsAndRollsBackRejectedBatchesInAllBackends() {
        try (var h2 = new StateStore(json, directory.toString());
                var memory = StateStore.volatileStore()) {
            for (var store : List.of(h2, memory)) {
                long epoch = store.leaderEpoch();
                store.put("test", "count", 1);
                assertEquals(2, store.<Integer>transaction(epoch, tx -> {
                    int current = tx.get("test", "count", Integer.class);
                    tx.put("test", "count", current + 1);
                    tx.put("test", "receipt", "accepted");
                    return tx.get("test", "count", Integer.class);
                }).intValue());
                assertThrows(IllegalArgumentException.class, () -> store.transaction(epoch, tx -> {
                    tx.put("test", "count", 999);
                    tx.remove("test", "receipt");
                    throw new IllegalArgumentException("CAS rejected");
                }));
                assertEquals(2, store.<Integer>transaction(epoch, tx -> tx.get("test", "count", Integer.class)).intValue());
                assertEquals(List.of("accepted"), store.transaction(epoch, tx -> {
                    assertNull(tx.get("test", "absent", String.class));
                    return tx.all("test", Object.class).stream().filter(String.class::isInstance).toList();
                }));
                assertUnavailable(() -> store.requireEpoch(epoch + 1));
                store.requireEpoch(epoch); // A caller with the wrong epoch does not poison storage.
            }
        }
    }

    @Test
    void reservedMetadataAndEscapedHandlesCannotBypassTheFenceOrPartiallyCommit() {
        try (var store = new StateStore(json, directory.toString())) {
            long epoch = store.leaderEpoch();
            for (var id : List.of("owner", "epoch", "future-metadata")) {
                assertThrows(IllegalArgumentException.class, () -> store.put("coordinator", id, "invalid"));
                assertThrows(IllegalArgumentException.class, () -> store.remove("coordinator", id));
            }
            assertThrows(IllegalArgumentException.class, () -> store.putBatch(List.of(
                    new StateStore.Write("test", "must-rollback", 1),
                    new StateStore.Write("coordinator", "epoch", 0))));
            assertTrue(store.all("test", Integer.class).isEmpty());
            var escaped = new AtomicReference<StateStore.Transaction>();
            store.transaction(epoch, tx -> { escaped.set(tx); return null; });
            assertThrows(IllegalStateException.class, () -> escaped.get().put("test", "outside", 1));
            assertThrows(IllegalStateException.class, () -> store.transaction(epoch, tx -> {
                tx.put("test", "nested", 1);
                store.put("test", "outside", 2);
                return null;
            }));
            assertTrue(store.all("test", Integer.class).isEmpty());
            store.requireEpoch(epoch);
        }
    }

    @Test
    void replacementCannotPassTheFenceBetweenTransactionReadAndCommit() throws Exception {
        try (var first = new StateStore(json, directory.toString());
                var executor = Executors.newSingleThreadExecutor()) {
            first.put("test", "count", 1);
            long epoch = first.leaderEpoch();
            var attempting = new CountDownLatch(1);
            var replacementRef = new AtomicReference<java.util.concurrent.Future<StateStore>>();
            first.transaction(epoch, tx -> {
                assertEquals(1, tx.get("test", "count", Integer.class));
                var replacement = executor.submit(() -> {
                    attempting.countDown();
                    return new StateStore(json, directory.toString());
                });
                replacementRef.set(replacement);
                try {
                    assertTrue(attempting.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> replacement.get(150, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                tx.put("test", "count", 2);
                return null;
            });
            try (var replacement = replacementRef.get().get(5, TimeUnit.SECONDS)) {
                assertEquals(epoch + 1, replacement.leaderEpoch());
                assertEquals(List.of(2), replacement.all("test", Integer.class));
                assertUnavailable(() -> first.transaction(epoch, tx -> tx.get("test", "count", Integer.class)));
            }
        }
    }

    @Test
    void onlyPersistedLegacyWorkerJobsReceiveTheMissingEpochDefault() {
        try (var store = new StateStore(json, directory.toString())) {
            var now = java.time.Instant.now();
            var job = new RemoteJobBroker.Job("legacy", "repair", "baseline", "digest",
                    java.util.Map.of("Main.java", "class Main {}"), "Main", now.plusSeconds(60),
                    "QUEUED", "", "", now, 0, null, 1);
            var old = (tools.jackson.databind.node.ObjectNode) json.valueToTree(job);
            old.remove("leaderEpoch");
            // The migration belongs only to stored jobs. Wire/model parsing remains strict.
            assertThrows(RuntimeException.class, () -> json.treeToValue(old, RemoteJobBroker.Job.class));
            store.put("worker-jobs", job.id(), old);
            assertEquals(0, store.all("worker-jobs", RemoteJobBroker.Job.class).getFirst().leaderEpoch());
            old.put("unexpectedField", true);
            store.put("worker-jobs", job.id(), old);
            assertThrows(RuntimeException.class, () -> store.all("worker-jobs", RemoteJobBroker.Job.class));
        }
    }

    @Test
    void invalidOrExhaustedEpochFailsClosedAndClosesTheFailedConstructorConnection() throws Exception {
        try (var store = new StateStore(json, directory.toString())) {
            store.put("test", "receipt", "safe");
        }
        for (var invalid : List.of("broken", "-1", Long.toString(Long.MAX_VALUE))) {
            try (var connection = raw(); var statement = connection.prepareStatement(
                    "UPDATE workbench_state SET payload=? WHERE bucket='coordinator' AND item_id='epoch'")) {
                statement.setString(1, invalid);
                statement.executeUpdate();
            }
            assertThrows(IllegalStateException.class, () -> new StateStore(json, directory.toString()));
            try (var connection = raw(); var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "failed constructor must not retain its connection");
            }
        }
        try (var connection = raw(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM workbench_state WHERE bucket='coordinator' AND item_id='epoch'");
        }
        assertThrows(IllegalStateException.class, () -> new StateStore(json, directory.toString()),
                "A previously migrated store must not silently reset a missing epoch");
    }

    private java.sql.Connection raw() throws Exception {
        return DriverManager.getConnection(
                "jdbc:h2:file:" + directory.resolve("state") + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "");
    }

    private static void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        assertEquals("STATE_UNAVAILABLE", assertThrows(PlatformException.class, action).code());
    }
}
