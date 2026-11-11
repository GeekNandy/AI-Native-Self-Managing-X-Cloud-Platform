package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class DurableWorkerRecoveryTest {
    @TempDir Path directory;
    final tools.jackson.databind.json.JsonMapper json = new WorkbenchConfiguration().jsonMapper();
    final ControlPlaneTest.MutableClock clock = new ControlPlaneTest.MutableClock();
    final Task task = new RepairTasks().examples().getFirst();
    final TestResult failed = new TestResult("FAIL", 1, false, false, 10, "baseline failed");
    final TestResult passed = new TestResult("PASS", 0, false, false, 10, "regression passed");
    final RepairPolicy policy = new RepairPolicy("mutation-v1", RepairSearch.DEFAULT_ORDER, List.of());

    RepairRun run(String id, String repository, String head) {
        return new RepairRun(id, repository, head, "Repair this regression", "mutation-search",
                policy.version(), "RUNNING", 12, 120, 0, clock.instant(), null, List.of(), null, null,
                List.of(), "", null, null, null, "Before crash");
    }

    void checkpoint(StateStore store, RepairRun run) {
        store.putBatch(List.of(new StateStore.Write("repair-runs", run.id(), run),
                new StateStore.Write("repair-checkpoints", run.id(), new RepairService.Checkpoint(run.id(), policy))));
    }

    RemoteJobBroker.Lease claim(RemoteJobBroker jobs, String worker) throws Exception {
        long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < stop) {
            var lease = jobs.claim(worker, "eclipse-temurin:21-jdk");
            if (lease != null) return lease;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected a queued test");
    }

    @Test
    void committedResultIsReusedAndAChangedInputCannotReuseItsStepKey() throws Exception {
        var run = run(UUID.randomUUID().toString(), "repository", "head");
        try (var store = new StateStore(json, directory.toString());
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            checkpoint(store, run);
            var jobs = new RemoteJobBroker(store, clock);
            var result = pool.submit(() -> jobs.test(task.files(), task.testClass(), Duration.ofSeconds(90), run.id(), "baseline"));
            var lease = claim(jobs, "first");
            jobs.complete(lease.id(), "first", new RemoteJobBroker.Completion(lease.fence(), lease.inputDigest(), failed));
            assertEquals(failed, result.get(3, TimeUnit.SECONDS));
        }
        try (var store = new StateStore(json, directory.toString())) {
            var recovered = new RemoteJobBroker(store, clock);
            assertEquals(failed, recovered.test(task.files(), task.testClass(), Duration.ofSeconds(70), run.id(), "baseline"));
            assertNull(recovered.claim("second", "eclipse-temurin:21-jdk"));
            assertEquals(1, recovered.summary().getFirst().get("deliveries"));
            var changed = new TreeMap<>(task.files());
            var path = changed.firstKey();
            changed.put(path, changed.get(path) + "\n");
            assertEquals("STEP_INPUT_CHANGED", assertThrows(PlatformException.class,
                    () -> recovered.test(changed, task.testClass(), Duration.ofSeconds(70), run.id(), "baseline")).code());
        }
    }

    @Test
    void takeoverRejectsOldWorkerAndRedeliversOnlyAfterItsLeaseExpires() throws Exception {
        var run = run(UUID.randomUUID().toString(), "repository", "head");
        try (var first = new StateStore(json, directory.toString());
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            checkpoint(first, run);
            var old = new RemoteJobBroker(first, clock);
            var waiting = pool.submit(() -> old.test(task.files(), task.testClass(), Duration.ofSeconds(90), run.id(), "baseline"));
            var stale = claim(old, "old-worker");
            try (var next = new StateStore(json, directory.toString())) {
                var recovered = new RemoteJobBroker(next, clock);
                assertEquals("STALE_LEASE", assertThrows(PlatformException.class, () -> recovered.complete(stale.id(),
                        "old-worker", new RemoteJobBroker.Completion(stale.fence(), stale.inputDigest(), passed))).code());
                assertNull(recovered.claim("new-worker", "eclipse-temurin:21-jdk"));
                clock.now = clock.now.plusSeconds(21);
                var retry = claim(recovered, "new-worker");
                assertEquals(stale.id(), retry.id());
                assertEquals(stale.deadline(), retry.deadline());
                assertNotEquals(stale.fence(), retry.fence());
                assertTrue(retry.fence().startsWith(next.leaderEpoch() + ":"));
                recovered.complete(retry.id(), "new-worker", new RemoteJobBroker.Completion(retry.fence(), retry.inputDigest(), passed));
                assertEquals(passed, recovered.test(task.files(), task.testClass(), Duration.ofSeconds(60), run.id(), "baseline"));
                assertEquals(2, recovered.summary().getFirst().get("deliveries"));
                assertThrows(ExecutionException.class, () -> waiting.get(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void restartedRepairReusesBaselineAndStopsForReviewWithoutCommitting() throws Exception {
        String id = UUID.randomUUID().toString(), repositoryId, head;
        try (var store = new StateStore(json, directory.toString());
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var repositories = new RepositoryWorkspace(store, new CommandRunner(), clock);
            var repository = repositories.create(task.name(), task.files(), task.testClass(), task.id());
            repositoryId = repository.id();
            head = repository.head();
            checkpoint(store, run(id, repositoryId, head));
            var jobs = new RemoteJobBroker(store, clock);
            var result = pool.submit(() -> jobs.test(task.files(), task.testClass(), Duration.ofSeconds(100), id, "baseline"));
            var lease = claim(jobs, "worker");
            jobs.complete(lease.id(), "worker", new RemoteJobBroker.Completion(lease.fence(), lease.inputDigest(), failed));
            assertEquals(failed, result.get(3, TimeUnit.SECONDS));
        }
        clock.now = clock.now.plusSeconds(10);
        try (var store = new StateStore(json, directory.toString())) {
            var jobs = new RemoteJobBroker(store, clock);
            var repositories = new RepositoryWorkspace(store, new CommandRunner(), clock);
            var compiler = new IntentCompiler(new PolicyEngine(), json);
            try (var repairs = new RepairService(repositories, jobs, new GeminiPatchPlanner("", "", json),
                    new RepairTasks(), store, new AuditLog(clock, store), clock, compiler)) {
                repairs.registerCapacityMetrics();
                var lease = claim(jobs, "recovered-worker");
                assertTrue(jobs.summary().stream().anyMatch(j -> j.get("id").equals(lease.id())
                        && j.get("phase").equals("attempt-1")));
                assertFalse(lease.deadline().isAfter(repairs.require(id).createdAt().plusSeconds(120)));
                jobs.complete(lease.id(), "recovered-worker", new RemoteJobBroker.Completion(lease.fence(), lease.inputDigest(), passed));
                long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (Set.of("QUEUED", "RUNNING").contains(repairs.require(id).status()) && System.nanoTime() < stop)
                    Thread.sleep(10);
                assertEquals("AWAITING_REVIEW", repairs.require(id).status());
                assertEquals(head, repositories.head(repositoryId));
                assertEquals(2, jobs.summary().size());
                assertEquals(1, repairs.require(id).attempts());
            }
        }
    }

    @Test
    void expiredAndGenerativeRunsAreNotResumable() {
        var run = run("run", "repository", "head");
        assertTrue(RepairService.recoverable(run, clock));
        var generative = new RepairRun(run.id(), run.repositoryId(), run.baseCommit(), run.instruction(),
                "gemini", run.policyVersion(), run.status(), run.maxAttempts(), run.timeoutSeconds(), run.attempts(),
                run.createdAt(), null, run.steps(), null, null, run.changes(), "", null, null, null, "");
        assertFalse(RepairService.recoverable(generative, clock));
        clock.now = clock.now.plusSeconds(120);
        assertFalse(RepairService.recoverable(run, clock));
    }

    @Test
    void legacyJobSchemaIsReadableButCannotBecomeAResumableLease() {
        try (var store = new StateStore(json, directory.toString())) {
            var legacy = new LinkedHashMap<String, Object>();
            legacy.put("id", "legacy"); legacy.put("owner", "unrecorded"); legacy.put("phase", "baseline");
            legacy.put("inputDigest", "old"); legacy.put("files", task.files()); legacy.put("testClass", task.testClass());
            legacy.put("deadline", clock.instant().plusSeconds(60)); legacy.put("status", "LEASED");
            legacy.put("worker", "old"); legacy.put("fence", "old-token"); legacy.put("leaseUntil", clock.instant().plusSeconds(20));
            legacy.put("deliveries", 1); legacy.put("result", null);
            store.put("worker-jobs", "legacy", legacy);
            var jobs = new RemoteJobBroker(store, clock);
            assertEquals("INTERRUPTED", jobs.summary().getFirst().get("status"));
            assertNull(jobs.claim("new-worker", "eclipse-temurin:21-jdk"));
        }
    }
}
