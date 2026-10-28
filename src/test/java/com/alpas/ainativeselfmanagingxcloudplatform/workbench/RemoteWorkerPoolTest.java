package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RemoteWorkerPoolTest {
    private final Map<String, String> files = Map.of(
            "src/main/java/Example.java", "class Example {}",
            "src/test/java/ExampleTest.java",
            "class ExampleTest { public static void main(String[] args) {} }");
    private final TestResult passed = new TestResult("PASS", 0, false, false, 1, "protocol fixture");

    private void complete(RemoteJobBroker jobs, String worker, RemoteJobBroker.Lease lease) {
        jobs.complete(lease.id(), worker,
                new RemoteJobBroker.Completion(lease.fence(), lease.inputDigest(), passed));
    }

    @Test
    void concurrentResultsSurviveBoundedHistoryEviction() throws Exception {
        var jobs = new RemoteJobBroker(StateStore.volatileStore(), Clock.systemUTC(),
                new ExecutionCapacity(4, 8, 4, 8, 12));
        var running = new AtomicBoolean(true);
        var firstLeases = new CountDownLatch(4);
        var deliveries = new ConcurrentHashMap<String, AtomicInteger>();
        var completed = new AtomicInteger();
        long started = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var consumers = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) {
                String worker = "worker-" + i;
                consumers.add(pool.submit(() -> {
                    while (running.get()) {
                        var lease = jobs.claim(worker, "eclipse-temurin:21-jdk");
                        if (lease == null) { Thread.sleep(2); continue; }
                        deliveries.computeIfAbsent(lease.id(), ignored -> new AtomicInteger()).incrementAndGet();
                        firstLeases.countDown();
                        assertTrue(firstLeases.await(5, TimeUnit.SECONDS), "Four simultaneous leases");
                        complete(jobs, worker, lease);
                    }
                    return null;
                }));
            }
            var clients = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < 8; i++) clients.add(pool.submit(() -> {
                    for (int n = 0; n < 10; n++) {
                        assertEquals(passed, jobs.test(files, "ExampleTest", Duration.ofSeconds(15)));
                        completed.incrementAndGet();
                    }
                }));
                for (var client : clients) client.get(25, TimeUnit.SECONDS);
            } finally {
                running.set(false);
                clients.forEach(client -> client.cancel(true));
            }
            for (var consumer : consumers) consumer.get(3, TimeUnit.SECONDS);
        }
        assertEquals(80, completed.get());
        assertEquals(80, deliveries.size());
        assertTrue(deliveries.values().stream().allMatch(count -> count.get() == 1));
        assertTrue(jobs.summary().size() <= 12);
        var report = Map.of(
                "experiment", "Coordinator protocol with simulated worker completions",
                "realContainerExecution", false, "workers", 4, "completedJobs", completed.get(),
                "retainedJobs", jobs.summary().size(),
                "elapsedMs", (System.nanoTime() - started) / 1_000_000);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/worker-pool-protocol-evidence.json"),
                new WorkbenchConfiguration().jsonMapper().writeValueAsString(report));
    }

    @Test
    void saturationRejectsNewWorkAndDrainingWorkerDoesNotClaimIt() throws Exception {
        var jobs = new RemoteJobBroker(StateStore.volatileStore(), Clock.systemUTC(),
                new ExecutionCapacity(2, 1, 2, 2, 4));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> jobs.test(files, "ExampleTest", Duration.ofSeconds(15)));
            var second = pool.submit(() -> jobs.test(files, "ExampleTest", Duration.ofSeconds(15)));
            try {
                long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (jobs.summary().size() < 2 && System.nanoTime() < stop) Thread.sleep(5);
                assertEquals(2, jobs.summary().size());
                assertEquals("WORKER_QUEUE_FULL", assertThrows(PlatformException.class,
                        () -> jobs.test(files, "ExampleTest", Duration.ofSeconds(15))).code());
                assertNull(jobs.claim("draining", "eclipse-temurin:21-jdk", Instant.now().plusSeconds(2)));
                complete(jobs, "available", jobs.claim("available", "eclipse-temurin:21-jdk"));
                complete(jobs, "available", jobs.claim("available", "eclipse-temurin:21-jdk"));
                assertEquals(passed, first.get(2, TimeUnit.SECONDS));
                assertEquals(passed, second.get(2, TimeUnit.SECONDS));
            } finally {
                first.cancel(true);
                second.cancel(true);
            }
        }
    }
}
