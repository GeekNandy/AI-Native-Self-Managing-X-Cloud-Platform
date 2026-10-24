package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class RemoteWorkerTest {
    final ControlPlaneTest.MutableClock clock = new ControlPlaneTest.MutableClock();
    final StateStore store = StateStore.volatileStore();
    final RemoteJobBroker jobs = new RemoteJobBroker(store, clock);
    final Map<String, String> files =
            Map.of(
                    "src/main/java/Example.java",
                    "class Example {}",
                    "src/test/java/ExampleTest.java",
                    "class ExampleTest { public static void main(String[] x) {} }");
    final TestResult passed = new TestResult("PASS", 0, false, false, 10, "one assertion passed");

    RemoteJobBroker.Lease claim(String worker) throws Exception {
        long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < stop) {
            var lease = jobs.claim(worker, "eclipse-temurin:21-jdk");
            if (lease != null) return lease;
            Thread.sleep(10);
        }
        throw new AssertionError("Job was not queued");
    }

    @Test
    void secondExpiredLeaseFailsTheJobAndDeadlineCannotBeRenewed() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = pool.submit(() -> jobs.test(files, "ExampleTest", Duration.ofSeconds(90)));
            claim("one");
            clock.now = clock.now.plusSeconds(21);
            var second = claim("two");
            clock.now = clock.now.plusSeconds(21);
            jobs.summary();
            assertEquals("WORKER_LOST", result.get(2, TimeUnit.SECONDS).status());
            assertThrows(
                    PlatformException.class,
                    () -> jobs.heartbeat(second.id(), "two", second.fence()));
        }
    }

    @Test
    void staleLeaseCannotCompleteAfterReassignmentAndDuplicateCompletionIsIdempotent()
            throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result =
                    pool.submit(
                            () ->
                                    jobs.test(
                                            files,
                                            "ExampleTest",
                                            Duration.ofSeconds(90),
                                            "run-a",
                                            "baseline"));
            var old = claim("worker-a");
            clock.now = clock.now.plusSeconds(21);
            var current = claim("worker-b");
            assertNotEquals(old.fence(), current.fence());
            assertEquals(
                    "STALE_LEASE",
                    assertThrows(
                                    PlatformException.class,
                                    () ->
                                            jobs.complete(
                                                    old.id(),
                                                    "worker-a",
                                                    new RemoteJobBroker.Completion(
                                                            old.fence(),
                                                            old.inputDigest(),
                                                            passed)))
                            .code());
            var receipt =
                    new RemoteJobBroker.Completion(current.fence(), current.inputDigest(), passed);
            jobs.complete(current.id(), "worker-b", receipt);
            jobs.complete(current.id(), "worker-b", receipt);
            assertEquals(passed, result.get(2, TimeUnit.SECONDS));
            assertThrows(
                    PlatformException.class,
                    () ->
                            jobs.complete(
                                    current.id(),
                                    "worker-b",
                                    new RemoteJobBroker.Completion(
                                            current.fence(), "0".repeat(64), passed)));
            assertEquals(2, jobs.summary().getFirst().get("deliveries"));
        }
    }

    @Test
    void cancellationRevokesTheLeaseAndDoesNotAcceptALateSuccess() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = pool.submit(() -> jobs.test(files, "ExampleTest", Duration.ofSeconds(30)));
            var lease = claim("worker");
            result.cancel(true);
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!jobs.summary().getFirst().get("status").equals("CANCELLED")
                    && System.nanoTime() < stop) Thread.sleep(10);
            assertEquals("CANCELLED", jobs.summary().getFirst().get("status"));
            assertThrows(
                    PlatformException.class,
                    () ->
                            jobs.complete(
                                    lease.id(),
                                    "worker",
                                    new RemoteJobBroker.Completion(
                                            lease.fence(), lease.inputDigest(), passed)));
        }
    }

    @Test
    void restartInterruptsJobsInsteadOfReplayingUnreviewedWork() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = pool.submit(() -> jobs.test(files, "ExampleTest", Duration.ofSeconds(30)));
            claim("worker");
            var restarted = new RemoteJobBroker(store, clock);
            assertEquals("INTERRUPTED", restarted.summary().getFirst().get("status"));
            assertNull(restarted.claim("new-worker", "eclipse-temurin:21-jdk"));
            result.cancel(true);
        }
    }

    @Test
    void misleadingPassAndOversizedOutputAreRejected() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = pool.submit(() -> jobs.test(files, "ExampleTest", Duration.ofSeconds(30)));
            var lease = claim("worker");
            for (var invalid :
                    List.of(
                            new TestResult("PASS", 1, false, false, 10, "failure"),
                            new TestResult("PASS", 0, false, true, 10, "truncated"),
                            new TestResult("FAIL", 1, false, false, 10, "x".repeat(65537))))
                assertEquals(
                        "INVALID_RESULT",
                        assertThrows(
                                        PlatformException.class,
                                        () ->
                                                jobs.complete(
                                                        lease.id(),
                                                        "worker",
                                                        new RemoteJobBroker.Completion(
                                                                lease.fence(),
                                                                lease.inputDigest(),
                                                                invalid)))
                                .code());
            result.cancel(true);
        }
    }
}
