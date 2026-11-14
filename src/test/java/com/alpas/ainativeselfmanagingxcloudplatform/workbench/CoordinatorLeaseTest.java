package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class CoordinatorLeaseTest {
    @TempDir Path directory;
    final tools.jackson.databind.json.JsonMapper json = new WorkbenchConfiguration().jsonMapper();

    @Test
    void replacingAWriterIncrementsEpochAndFencesReadsWritesAndReadiness() {
        long previous;
        try (var first = new StateStore(json, directory.toString())) {
            previous = first.leaderEpoch();
            first.put("test", "receipt", "committed");
            try (var next = new StateStore(json, directory.toString())) {
                assertEquals(previous + 1, next.leaderEpoch());
                assertEquals(List.of("committed"), next.all("test", String.class));
                assertEquals("STATE_UNAVAILABLE", assertThrows(PlatformException.class,
                        () -> first.put("test", "receipt", "stale")).code());
                assertThrows(PlatformException.class, () -> first.all("test", String.class));
                assertThrows(PlatformException.class, () -> first.compareAndSet("test", "receipt", "committed", "stale", String.class));
                assertEquals(List.of("committed"), next.all("test", String.class));
            }
        }
        try (var restarted = new StateStore(json, directory.toString())) {
            assertEquals(previous + 2, restarted.leaderEpoch());
        }
    }

    @Test
    void conditionalTransitionsCannotOverwriteACommittedReceipt() {
        try (var store = new StateStore(json, directory.toString())) {
            assertTrue(store.compareAndSet("test", "job", null, "queued", String.class));
            assertTrue(store.compareAndSet("test", "job", "queued", "complete", String.class));
            assertFalse(store.compareAndSet("test", "job", "queued", "cancelled", String.class));
            assertFalse(store.compareAndSet("test", "job", null, "duplicate", String.class));
            store.requireAvailable();
            assertEquals(List.of("complete"), store.all("test", String.class));
        }
    }

    @Test
    void expiryRefusesWritesEvenWithoutASuccessor() throws Exception {
        try (var store = new StateStore(json, directory.toString());
                var db = DriverManager.getConnection("jdbc:h2:file:" + directory.resolve("state"), "sa", "")) {
            store.put("test", "receipt", "before");
            try (var sql = db.createStatement()) {
                sql.execute("UPDATE workbench_leadership SET lease_until=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00'");
            }
            assertThrows(PlatformException.class, () -> store.put("test", "receipt", "after"));
            assertEquals(false, store.status().get("available"));
            try (var next = new StateStore(json, directory.toString())) {
                assertEquals(List.of("before"), next.all("test", String.class));
            }
        }
    }

    @Test
    void embeddedLeaseExpiryIsNotFrozenAtTransactionStart() {
        try (var store = new StateStore(json, directory.toString(), "", "single", 5, 0)) {
            store.put("test", "receipt", "before");
            long epoch = store.leaderEpoch();
            assertThrows(PlatformException.class, () -> store.transaction(epoch, tx -> {
                tx.put("test", "receipt", "expired");
                try {
                    Thread.sleep(5_100);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return null;
            }));
        }
        try (var next = new StateStore(json, directory.toString())) {
            assertEquals(List.of("before"), next.all("test", String.class));
        }
    }

    @Test
    void contendersElectOneOwnerAndAnExpiredEpochCannotBeRenewed() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var first = DriverManager.getConnection(url); var second = DriverManager.getConnection(url);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = new CoordinatorLease(first, false, "one", Duration.ofSeconds(30));
            var b = new CoordinatorLease(second, false, "two", Duration.ofSeconds(30));
            a.initialize();
            b.initialize();
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); boolean won = a.acquire(false); first.commit(); return won; });
            var two = pool.submit(() -> { start.await(); boolean won = b.acquire(false); second.commit(); return won; });
            start.countDown();
            assertNotEquals(one.get(3, TimeUnit.SECONDS), two.get(3, TimeUnit.SECONDS));
            var winner = one.get() ? a : b;
            var loser = one.get() ? b : a;
            var ownerDb = one.get() ? first : second;
            var nextDb = one.get() ? second : first;
            winner.renew();
            ownerDb.commit();
            assertFalse(loser.acquire(false));
            nextDb.rollback();
            try (var sql = ownerDb.createStatement()) {
                sql.execute("UPDATE workbench_leadership SET lease_until=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00'");
            }
            ownerDb.commit();
            assertThrows(IllegalStateException.class, winner::renew);
            ownerDb.rollback();
            assertTrue(loser.acquire(false));
            nextDb.commit();
            assertEquals(winner.epoch() + 1, loser.epoch());
            assertThrows(IllegalStateException.class, winner::assertOwned);
            ownerDb.rollback();
        }
    }

    @Test
    void leaseModeRejectsEmbeddedStorageAndUnboundedConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new StateStore(json, "memory", "", "lease", 30, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new StateStore(json, "memory", "", "single", 30, 601));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchConfiguration.validateCoordination("lease", "docker", "remote", ""));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchConfiguration.validateCoordination("lease", "sandbox", "local", ""));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchConfiguration.validateCoordination("lease", "sandbox", "remote", "owner/repo"));
        assertDoesNotThrow(() -> WorkbenchConfiguration.validateCoordination("lease", "sandbox", "remote", ""));
    }
}
