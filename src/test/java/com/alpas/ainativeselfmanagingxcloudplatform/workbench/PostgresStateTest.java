package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PostgresStateTest {
    private final tools.jackson.databind.json.JsonMapper json =
            new WorkbenchConfiguration().jsonMapper();

    @Test
    void replacementCoordinatorFencesOldReadsAndWritesAndRestoresGitFromDatabase() {
        try (var first = open()) {
            long epoch = first.leaderEpoch();
            var workspace = new RepositoryWorkspace(first, new CommandRunner(), Clock.systemUTC());
            var task = new RepairTasks().examples().getFirst();
            var repo = workspace.create(task.name(), task.files(), task.testClass(), task.id());
            try (var replacement = open()) {
                assertEquals(epoch + 1, replacement.leaderEpoch());
                assertUnavailable(() -> first.put("test", "stale", task));
                assertUnavailable(() -> first.all("test", CodeModel.Task.class));
                assertUnavailable(() -> first.remove("repositories", repo.id()));
                assertUnavailable(() -> first.requireEpoch(epoch));
                var restored = new RepositoryWorkspace(replacement, new CommandRunner(), Clock.systemUTC());
                assertEquals(repo.head(), restored.head(repo.id()));
                assertEquals(task.files(), restored.files(repo.id()));
                replacement.put("test", "receipt", task);
                assertEquals(task, replacement.all("test", CodeModel.Task.class).getFirst());
                replacement.remove("test", "receipt");
            }
        }
    }

    @Test
    void atomicComparedBatchRollsBackOnRejectionAndCannotRewriteOwnership() {
        String bucket = "epoch-test-" + UUID.randomUUID();
        try (var store = open()) {
            long epoch = store.leaderEpoch();
            store.put(bucket, "count", 1);
            assertEquals(2, store.<Integer>transaction(epoch, tx -> {
                assertEquals(1, tx.get(bucket, "count", Integer.class));
                tx.put(bucket, "count", 2);
                tx.put(bucket, "receipt", "committed");
                return tx.get(bucket, "count", Integer.class);
            }).intValue());
            assertThrows(IllegalArgumentException.class, () -> store.transaction(epoch, tx -> {
                tx.put(bucket, "count", 99);
                tx.remove(bucket, "receipt");
                throw new IllegalArgumentException("CAS rejected");
            }));
            assertEquals(2, store.<Integer>transaction(epoch, tx -> tx.get(bucket, "count", Integer.class)).intValue());
            assertEquals("committed", store.transaction(epoch, tx -> tx.get(bucket, "receipt", String.class)));
            for (var id : List.of("owner", "epoch")) {
                assertThrows(IllegalArgumentException.class, () -> store.put("coordinator", id, "invalid"));
                assertThrows(IllegalArgumentException.class, () -> store.remove("coordinator", id));
            }
            assertThrows(IllegalArgumentException.class, () -> store.putBatch(List.of(
                    new StateStore.Write(bucket, "must-rollback", 1),
                    new StateStore.Write("coordinator", "epoch", 0))));
            assertNull(store.transaction(epoch, tx -> tx.get(bucket, "must-rollback", Integer.class)));
            store.requireEpoch(epoch);
        }
    }

    @Test
    void replacementWaitsForTransactionCommitAndCannotAcceptStaleCallbacks() throws Exception {
        String bucket = "epoch-lock-" + UUID.randomUUID();
        try (var first = open(); var executor = Executors.newSingleThreadExecutor()) {
            long epoch = first.leaderEpoch();
            first.put(bucket, "count", 1);
            var attempting = new CountDownLatch(1);
            var future = new AtomicReference<java.util.concurrent.Future<StateStore>>();
            first.transaction(epoch, tx -> {
                assertEquals(1, tx.get(bucket, "count", Integer.class));
                var replacement = executor.submit(() -> {
                    attempting.countDown();
                    return open();
                });
                future.set(replacement);
                try {
                    assertTrue(attempting.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> replacement.get(150, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                tx.put(bucket, "count", 2);
                return null;
            });
            try (var replacement = future.get().get(10, TimeUnit.SECONDS)) {
                assertEquals(epoch + 1, replacement.leaderEpoch());
                assertEquals(List.of(2), replacement.all(bucket, Integer.class));
                var invoked = new java.util.concurrent.atomic.AtomicBoolean();
                assertUnavailable(() -> first.transaction(epoch, tx -> {
                    invoked.set(true);
                    tx.put(bucket, "count", 999);
                    return null;
                }));
                assertFalse(invoked.get(), "A replaced owner must not execute a transaction callback");
                assertEquals(List.of(2), replacement.all(bucket, Integer.class));
            }
        }
    }

    @Test
    void simultaneousReplacementsAllocateDistinctIncreasingDurableEpochs() throws Exception {
        long original;
        try (var first = open()) { original = first.leaderEpoch(); }
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try (var store = open()) {
                    // This diagnostic identifies the acquired epoch even if another startup has
                    // already replaced it. Operational reads still require the current fence.
                    return (Long) store.status().get("leaderEpoch");
                }
            }));
            start.countDown();
            var epochs = new java.util.TreeSet<Long>();
            for (var future : futures) epochs.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(original + 1, original + 2, original + 3, original + 4), epochs);
        }
        try (var restarted = open()) { assertEquals(original + 5, restarted.leaderEpoch()); }
    }

    @Test
    void legacyRawUuidOwnerUpgradesWithoutDiscardingRecords() throws Exception {
        String bucket = "epoch-legacy-" + UUID.randomUUID();
        try (var first = open()) { first.put(bucket, "receipt", "legacy"); }
        try (var connection = rawConnection(); var statement = connection.createStatement()) {
            // Simulate the exact metadata schema of the previous release on the disposable test DB.
            statement.executeUpdate("DELETE FROM workbench_state WHERE bucket='coordinator' AND item_id IN ('epoch','epoch-format')");
            statement.executeUpdate("UPDATE workbench_state SET payload='00000000-0000-0000-0000-000000000001' WHERE bucket='coordinator' AND item_id='owner'");
        }
        try (var upgraded = open()) {
            assertEquals(1, upgraded.leaderEpoch());
            assertEquals(List.of("legacy"), upgraded.all(bucket, String.class));
        }
        try (var restarted = open()) { assertEquals(2, restarted.leaderEpoch()); }
    }

    private StateStore open() {
        return new StateStore(json, "memory", System.getenv("TEST_DATABASE_URL"));
    }

    private Connection rawConnection() throws Exception {
        String value = System.getenv("TEST_DATABASE_URL");
        if (value.startsWith("jdbc:postgresql://")) return DriverManager.getConnection(value);
        var uri = java.net.URI.create(value);
        var parts = uri.getRawUserInfo().split(":", 2);
        var properties = new java.util.Properties();
        properties.setProperty("user", java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8));
        properties.setProperty("password", parts.length == 2
                ? java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8) : "");
        return DriverManager.getConnection("jdbc:postgresql://" + uri.getHost()
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()), properties);
    }

    private static void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        assertEquals("STATE_UNAVAILABLE", assertThrows(PlatformException.class, action).code());
    }
}
