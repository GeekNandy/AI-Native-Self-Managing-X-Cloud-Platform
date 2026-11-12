package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Durable real H2 transactions; worker results are protocol fixtures, not Docker evidence. */
@org.junit.jupiter.api.Timeout(30)
class RemoteJobRecoveryTest {
    @TempDir Path directory;
    final tools.jackson.databind.json.JsonMapper json = new WorkbenchConfiguration().jsonMapper();
    final ControlPlaneTest.MutableClock clock = new ControlPlaneTest.MutableClock();
    final Map<String, String> files = Map.of(
            "src/main/java/Example.java", "class Example {}",
            "src/test/java/ExampleTest.java", "class ExampleTest { public static void main(String[] args) {} }");
    final CodeModel.TestResult passed = new CodeModel.TestResult("PASS", 0, false, false, 10, "protocol fixture");

    StateStore open() { return new StateStore(json, directory.toString()); }
    String submit(RemoteJobBroker jobs) {
        return jobs.submit(files, "ExampleTest", Duration.ofSeconds(90), "repair-a", "baseline");
    }
    RemoteJobBroker.Lease claim(RemoteJobBroker jobs, String worker) {
        var lease = jobs.claim(worker, "eclipse-temurin:21-jdk");
        assertNotNull(lease);
        return lease;
    }
    void complete(RemoteJobBroker jobs, RemoteJobBroker.Lease lease, String worker) {
        jobs.complete(lease.id(), worker, new RemoteJobBroker.Completion(
                lease.fence(), lease.inputDigest(), passed, lease.leaderEpoch()));
    }
    void stale(org.junit.jupiter.api.function.Executable action) {
        assertEquals("STALE_LEASE", assertThrows(PlatformException.class, action).code());
    }

    @Test
    void queuedJobSurvivesRestartAndResubmissionPreservesIdentityInputsAndDeadline() {
        String id;
        Object deadline;
        long epoch;
        try (var store = open()) {
            var jobs = new RemoteJobBroker(store, clock);
            id = submit(jobs);
            deadline = jobs.receipt(id).get("deadline");
            epoch = store.leaderEpoch();
        }
        clock.now = clock.now.plusSeconds(10);
        try (var store = open()) {
            var jobs = new RemoteJobBroker(store, clock);
            assertEquals(epoch + 1, store.leaderEpoch());
            assertEquals(id, submit(jobs));
            assertEquals(deadline, jobs.receipt(id).get("deadline"));
            var changed = new HashMap<>(files);
            changed.put("src/main/java/Example.java", "class Example { int changed; }");
            assertEquals("JOB_INPUT_CHANGED", assertThrows(PlatformException.class,
                    () -> jobs.submit(changed, "ExampleTest", Duration.ofSeconds(90), "repair-a", "baseline")).code());
            var lease = claim(jobs, "worker");
            assertEquals(id, lease.id());
            assertEquals(files, lease.files());
            assertEquals(deadline, lease.deadline());
            assertEquals(store.leaderEpoch(), lease.leaderEpoch());
            complete(jobs, lease, "worker");
            assertEquals(passed, jobs.await(id));
        }
    }

    @Test
    void replacementFencesOldBrokerAndWorkerThenRetainsCompletedReceiptAcrossAnotherRestart() {
        String id;
        long completedEpoch;
        try (var first = open()) {
            var old = new RemoteJobBroker(first, clock);
            id = submit(old);
            var lost = claim(old, "old-worker");
            try (var replacement = open()) {
                var current = new RemoteJobBroker(replacement, clock);
                completedEpoch = replacement.leaderEpoch();
                assertEquals("STATE_UNAVAILABLE", assertThrows(PlatformException.class,
                        () -> old.claim("stale", "eclipse-temurin:21-jdk")).code());
                assertThrows(PlatformException.class, () -> old.receipt(id));
                assertThrows(PlatformException.class, () -> old.await(id));
                stale(() -> current.heartbeat(id, "old-worker", lost.fence(), lost.leaderEpoch()));
                stale(() -> complete(current, lost, "old-worker"));
                var lease = claim(current, "new-worker");
                assertNotEquals(lost.fence(), lease.fence());
                assertEquals(lost.deadline(), lease.deadline());
                assertEquals(2, current.receipt(id).get("deliveries"));
                complete(current, lease, "new-worker");
                complete(current, lease, "new-worker");
                assertEquals(passed, current.await(id));
            }
        }
        try (var store = open()) {
            var restarted = new RemoteJobBroker(store, clock);
            assertEquals(id, submit(restarted));
            assertEquals(completedEpoch, restarted.receipt(id).get("leaderEpoch"));
            assertEquals(passed, restarted.await(id));
            assertNull(restarted.claim("unused", "eclipse-temurin:21-jdk"));
        }
    }

    @Test
    void repeatedReplacementDoesNotResetDeliveryLimit() {
        String id;
        try (var first = open()) {
            var jobs = new RemoteJobBroker(first, clock);
            id = submit(jobs);
            claim(jobs, "one");
        }
        try (var second = open()) {
            claim(new RemoteJobBroker(second, clock), "two");
        }
        try (var third = open()) {
            var jobs = new RemoteJobBroker(third, clock);
            assertEquals("WORKER_LOST", jobs.await(id).status());
            assertEquals(2, jobs.receipt(id).get("deliveries"));
            assertNull(jobs.claim("three", "eclipse-temurin:21-jdk"));
        }
    }

    @Test
    void expiredJobCannotGainANewBudgetOnRestart() {
        String id;
        try (var first = open()) { id = submit(new RemoteJobBroker(first, clock)); }
        clock.now = clock.now.plusSeconds(91);
        try (var second = open()) {
            var jobs = new RemoteJobBroker(second, clock);
            assertEquals(id, submit(jobs));
            assertEquals("TIMEOUT", jobs.await(id).status());
            assertNull(jobs.claim("worker", "eclipse-temurin:21-jdk"));
        }
    }

    @Test
    void concurrentBrokerInstancesCannotClaimTheSameDurableJob() throws Exception {
        try (var store = open(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = new RemoteJobBroker(store, clock);
            var second = new RemoteJobBroker(store, clock);
            submit(first);
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return first.claim("a", "eclipse-temurin:21-jdk"); });
            var b = pool.submit(() -> { start.await(); return second.claim("b", "eclipse-temurin:21-jdk"); });
            start.countDown();
            var one = a.get(5, TimeUnit.SECONDS);
            var two = b.get(5, TimeUnit.SECONDS);
            assertTrue((one == null) != (two == null), "Exactly one atomic claim succeeds");
        }
    }

    @Test
    void legacyJobGetsAnEpochWithoutLosingItsExistingIdOrInputs() {
        try (var store = open()) {
            String legacyId = UUID.randomUUID().toString();
            String digest = IntentCompiler.digest(json.writeValueAsString(List.of(new TreeMap<>(files), "ExampleTest")));
            var node = json.valueToTree(new RemoteJobBroker.Job(legacyId, "repair-a", "baseline", digest,
                    files, "ExampleTest", clock.now.plusSeconds(90), "QUEUED", "", "", clock.now, 0, null, 0));
            ((tools.jackson.databind.node.ObjectNode) node).remove("leaderEpoch");
            store.put("worker-jobs", legacyId, node);
            var jobs = new RemoteJobBroker(store, clock);
            assertEquals(legacyId, submit(jobs));
            assertEquals(1, jobs.summary().size());
            var lease = claim(jobs, "worker");
            assertEquals(legacyId, lease.id());
            assertEquals(store.leaderEpoch(), lease.leaderEpoch());
            complete(jobs, lease, "worker");
        }
    }

    @Test
    void dispatchAndReceiptEvictionUseOriginalDeadlineRatherThanHashOrder() {
        try (var store = StateStore.volatileStore()) {
            var jobs = new RemoteJobBroker(store, clock, new ExecutionCapacity(1, 1, 2, 2, 3));
            // Arrange the longer deadline to have the smaller ID, so accidental hash order
            // cannot satisfy the dispatch or receipt-retention assertions.
            var owners = new ArrayList<>(List.of("deadline-a", "deadline-b"));
            owners.sort(Comparator.comparing(owner -> IntentCompiler.digest(
                    json.writeValueAsString(List.of(owner, "baseline")))));
            String longer = jobs.submit(files, "ExampleTest", Duration.ofSeconds(90), owners.getFirst(), "baseline");
            String sooner = jobs.submit(files, "ExampleTest", Duration.ofSeconds(30), owners.getLast(), "baseline");
            assertTrue(longer.compareTo(sooner) < 0);
            var first = claim(jobs, "worker");
            assertEquals(sooner, first.id());
            complete(jobs, first, "worker");
            var second = claim(jobs, "worker");
            assertEquals(longer, second.id());
            complete(jobs, second, "worker");
            jobs.submit(files, "ExampleTest", Duration.ofSeconds(100), "fill", "baseline");
            jobs.submit(files, "ExampleTest", Duration.ofSeconds(100), "rotate", "baseline");
            assertEquals("JOB_NOT_FOUND", assertThrows(PlatformException.class, () -> jobs.receipt(sooner)).code());
            assertEquals("COMPLETED", jobs.receipt(longer).get("status"));
        }
    }

    @Test
    void concurrentReattachmentRetainsReceiptWhileHistoryIsUnderPressure() throws Exception {
        try (var store = StateStore.volatileStore(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new RemoteJobBroker(store, clock, new ExecutionCapacity(1, 1, 1, 1, 2));
            String id = submit(jobs);
            var threads = new CopyOnWriteArrayList<Thread>();
            var waiters = new ArrayList<Future<CodeModel.TestResult>>();
            for (int i = 0; i < 4; i++) waiters.add(pool.submit(() -> {
                threads.add(Thread.currentThread());
                return jobs.await(id);
            }));
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((threads.size() < 4 || threads.stream().anyMatch(t -> t.getState() != Thread.State.TIMED_WAITING))
                    && System.nanoTime() < stop) Thread.sleep(5);
            assertEquals(4, threads.size());
            assertTrue(threads.stream().allMatch(t -> t.getState() == Thread.State.TIMED_WAITING));
            var initial = claim(jobs, "worker");
            synchronized (jobs) {
                complete(jobs, initial, "worker");
                // Fill and rotate bounded history before any waiter can collect its result.
                for (int i = 0; i < 4; i++) {
                    jobs.submit(files, "ExampleTest", Duration.ofSeconds(90), "other-" + i, "baseline");
                    complete(jobs, claim(jobs, "worker"), "worker");
                }
                assertEquals("COMPLETED", jobs.receipt(id).get("status"));
            }
            var churn = pool.submit(() -> {
                for (int i = 4; i < 40; i++) {
                    jobs.submit(files, "ExampleTest", Duration.ofSeconds(90), "other-" + i, "baseline");
                    complete(jobs, claim(jobs, "worker"), "worker");
                }
            });
            for (var waiter : waiters) assertEquals(passed, waiter.get(5, TimeUnit.SECONDS));
            churn.get(5, TimeUnit.SECONDS);
            assertTrue(jobs.summary().size() <= 2);
        }
    }

    @Test
    void rejectedCompletionDoesNotChangeDurableLeaseAndClosedStoreCannotAcknowledgeIt() {
        try (var store = open()) {
            var jobs = new RemoteJobBroker(store, clock);
            String id = submit(jobs);
            var lease = claim(jobs, "worker");
            var malformed = new CodeModel.TestResult(null, 0, false, false, 0, "invalid");
            assertEquals("INVALID_RESULT", assertThrows(PlatformException.class,
                    () -> jobs.complete(id, "worker", new RemoteJobBroker.Completion(
                            lease.fence(), lease.inputDigest(), malformed, lease.leaderEpoch()))).code());
            assertEquals("LEASED", jobs.receipt(id).get("status"));
            store.close();
            assertEquals("STATE_UNAVAILABLE", assertThrows(PlatformException.class,
                    () -> complete(jobs, lease, "worker")).code());
        }
    }
}
