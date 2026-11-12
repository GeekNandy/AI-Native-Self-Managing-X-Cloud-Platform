package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/**
 * Durable isolated-test queue. Epoch-bound lease tokens and conditional transitions fence late results.
 */
@Component
public final class RemoteJobBroker implements SandboxExecutor {
    public record Job(
            String id,
            String owner,
            String phase,
            String inputDigest,
            Map<String, String> files,
            String testClass,
            Instant deadline,
            String status,
            String worker,
            String fence,
            Instant leaseUntil,
            int deliveries,
            TestResult result,
            long leaderEpoch) {}

    public record Lease(
            String id,
            String fence,
            String inputDigest,
            Map<String, String> files,
            String testClass,
            Instant deadline) {}

    public record Completion(String fence, String inputDigest, TestResult result) {}

    public record Worker(String id, Instant seenAt, String image, Instant acceptingUntil) {}

    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Worker> workers = new HashMap<>();
    private final Set<String> awaitingResults = new HashSet<>();
    private final ExecutionCapacity capacity;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry metrics;

    private final StateStore store;
    private final Clock clock;

    public RemoteJobBroker(StateStore store, Clock clock) {
        this(store, clock, ExecutionCapacity.defaults());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RemoteJobBroker(StateStore store, Clock clock, ExecutionCapacity capacity) {
        this.capacity = capacity;
        this.store = store;
        this.clock = clock;
        var recoverable = new HashSet<String>();
        var checkpoints = new HashMap<String, RepairService.Checkpoint>();
        store.all("repair-checkpoints", RepairService.Checkpoint.class).forEach(c -> checkpoints.put(c.runId(), c));
        for (var run : store.all("repair-runs", RepairRun.class)) {
            var checkpoint = checkpoints.get(run.id());
            if (checkpoint != null && checkpoint.policy().version().equals(run.policyVersion())
                    && RepairService.recoverable(run, clock)) recoverable.add(run.id());
        }
        for (var job : store.all("worker-jobs", Job.class)) {
            jobs.put(job.id(), job);
            if (active(job) && !recoverable.contains(job.owner()))
                save(changed(job, "INTERRUPTED", "", "", clock.instant(), job.deliveries(),
                        failure("INTERRUPTED", "No resumable repair checkpoint; submit a new repair.")));
            else if (job.status().equals("QUEUED"))
                save(changed(job, "QUEUED", "", "", clock.instant(), job.deliveries(), null));
            // Keep an old leased job until expiry; reject its old epoch immediately. Only the
            // isolated test may be redelivered, never an approval, Git commit or provider write.
        }
    }

    private boolean active(Job j) {
        return Set.of("QUEUED", "LEASED").contains(j.status());
    }

    private static TestResult failure(String status, String detail) {
        return new TestResult(status, -1, status.equals("TIMEOUT"), false, 0, detail);
    }

    private Job changed(
            Job j,
            String status,
            String worker,
            String fence,
            Instant lease,
            int deliveries,
            TestResult result) {
        return new Job(
                j.id(),
                j.owner(),
                j.phase(),
                j.inputDigest(),
                activeStatus(status) ? j.files() : Map.of(),
                j.testClass(),
                j.deadline(),
                status,
                worker,
                fence,
                lease,
                deliveries,
                result,
                store.leaderEpoch());
    }

    private static boolean activeStatus(String s) {
        return s.equals("QUEUED") || s.equals("LEASED");
    }

    private void save(Job j) {
        var previous = jobs.get(j.id());
        if (previous != null && !active(previous) && !previous.equals(j))
            throw new PlatformException(409, "JOB_TERMINAL", "A terminal worker receipt cannot be overwritten.");
        if (!store.compareAndSet("worker-jobs", j.id(), previous, j, Job.class))
            throw new PlatformException(409, "JOB_CHANGED", "The durable job changed; reload before retrying.");
        if (metrics != null && (previous == null || !previous.status().equals(j.status())))
            metrics.counter("platform.worker.jobs", "state", j.status()).increment();
        jobs.put(j.id(), j);
        synchronized (this) { notifyAll(); }
    }

    private void reap() {
        store.requireAvailable();
        var now = clock.instant();
        for (var j : List.copyOf(jobs.values())) {
            if (!active(j)) continue;
            if (!now.isBefore(j.deadline()))
                save(
                        changed(
                                j,
                                "TIMEOUT",
                                j.worker(),
                                j.fence(),
                                now,
                                j.deliveries(),
                                failure("TIMEOUT", "Execution deadline exceeded.")));
            else if (j.status().equals("LEASED") && !now.isBefore(j.leaseUntil())) {
                boolean retry = j.deliveries() < 2;
                save(
                        changed(
                                j,
                                retry ? "QUEUED" : "WORKER_LOST",
                                "",
                                "",
                                now,
                                j.deliveries(),
                                retry
                                        ? null
                                        : failure(
                                                "WORKER_LOST", "Worker heartbeat expired twice.")));
            }
        }
        workers.values().removeIf(w -> w.seenAt().isBefore(now.minusSeconds(60))
                || !now.isBefore(w.acceptingUntil()));
    }

    public synchronized void register(String worker, String image) {
        register(worker, image, Instant.MAX);
    }

    private void register(String worker, String image, Instant acceptingUntil) {
        reap();
        if (image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]{0,200}"))
            throw new PlatformException(
                    400, "INVALID_WORKER", "Provide the preloaded sandbox image.");
        if (!workers.containsKey(worker) && workers.size() >= capacity.remoteWorkers())
            throw new PlatformException(429, "WORKER_LIMIT", "The configured worker limit is reached.");
        workers.put(worker, new Worker(worker, clock.instant(), image, acceptingUntil));
    }

    public synchronized Lease claim(String worker, String image) {
        return claim(worker, image, Instant.MAX);
    }

    public synchronized Lease claim(String worker, String image, Instant acceptingUntil) {
        if (acceptingUntil == null || (!acceptingUntil.equals(Instant.MAX)
                && acceptingUntil.isAfter(clock.instant().plusSeconds(18300))))
            throw new PlatformException(400, "INVALID_WORKER", "Invalid worker acceptance window.");
        register(worker, image, acceptingUntil);
        if (jobs.values().stream()
                .anyMatch(j -> j.status().equals("LEASED") && j.worker().equals(worker)))
            return null;
        for (var j : jobs.values())
            if (j.status().equals("QUEUED")
                    && !j.deadline().isAfter(acceptingUntil)
                    && (!j.files().containsKey("pom.xml")
                            || image.equals("xcloud-java21-runner:1"))) {
                var leased =
                        changed(
                                j,
                                "LEASED",
                                worker,
                                store.leaderEpoch() + ":" + UUID.randomUUID(),
                                clock.instant().plusSeconds(20),
                                j.deliveries() + 1,
                                null);
                save(leased);
                return new Lease(
                        j.id(),
                        leased.fence(),
                        j.inputDigest(),
                        j.files(),
                        j.testClass(),
                        j.deadline());
            }
        return null;
    }

    public synchronized void heartbeat(String id, String worker, String fence) {
        reap();
        var j = authorized(id, worker, fence);
        if (!j.status().equals("LEASED"))
            throw new PlatformException(409, "LEASE_ENDED", "This execution lease has ended.");
        workers.computeIfPresent(worker, (k, w) -> new Worker(
                k, clock.instant(), w.image(), w.acceptingUntil()));
        save(
                changed(
                        j,
                        j.status(),
                        worker,
                        fence,
                        clock.instant().plusSeconds(20),
                        j.deliveries(),
                        null));
    }

    public synchronized void complete(String id, String worker, Completion c) {
        reap();
        var j = authorized(id, worker, c.fence());
        if (!j.inputDigest().equals(c.inputDigest()))
            throw new PlatformException(
                    409, "INPUT_MISMATCH", "The result belongs to different inputs.");
        var r = c.result();
        if (r == null
                || r.output() == null
                || r.output().length() > 65536
                || r.durationMs() < 0
                || r.durationMs() > 180000
                || !Set.of("PASS", "FAIL", "TIMEOUT", "ERROR").contains(r.status())
                || (r.passed() && (r.exitCode() != 0 || r.timedOut() || r.truncated())))
            throw new PlatformException(
                    400, "INVALID_RESULT", "Worker result violates the execution contract.");
        if (j.status().equals("COMPLETED") && Objects.equals(j.result(), r)) return;
        if (!j.status().equals("LEASED"))
            throw new PlatformException(409, "LEASE_ENDED", "This execution lease has ended.");
        save(changed(j, "COMPLETED", worker, c.fence(), clock.instant(), j.deliveries(), r));
    }

    private Job authorized(String id, String worker, String fence) {
        var j = jobs.get(id);
        if (j == null || j.leaderEpoch() != store.leaderEpoch()
                || !j.worker().equals(worker) || !j.fence().equals(fence))
            throw new PlatformException(
                    409, "STALE_LEASE", "Worker identity or lease token is stale.");
        return j;
    }

    public synchronized Map<String, Object> capabilities() {
        reap();
        var online =
                workers.values().stream()
                        .filter(w -> w.seenAt().isAfter(clock.instant().minusSeconds(20)))
                        .filter(w -> w.acceptingUntil().isAfter(clock.instant().plusSeconds(20)))
                        .toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("available", !online.isEmpty());
        result.put("mode", "remote");
        result.put("network", "none");
        result.put("hostMounts", false);
        result.put("workers", online);
        result.put("maxWorkers", capacity.remoteWorkers());
        result.put("maxInFlight", capacity.remoteJobs());
        result.put("queued", jobs.values().stream().filter(j -> j.status().equals("QUEUED")).count());
        result.put("leased", jobs.values().stream().filter(j -> j.status().equals("LEASED")).count());
        result.put("retainedJobs", jobs.size());
        result.put("detail", "Authenticated Docker workers; epoch-bound 20-second leases, two deliveries, durable queue.");
        return result;
    }

    public synchronized List<Map<String, Object>> summary() {
        reap();
        return jobs.values().stream()
                .map(
                        j ->
                                Map.<String, Object>of(
                                        "id",
                                        j.id(),
                                        "owner",
                                        j.owner(),
                                        "phase",
                                        j.phase(),
                                        "inputDigest",
                                        j.inputDigest(),
                                        "status",
                                        j.status(),
                                        "deliveries",
                                        j.deliveries(),
                                        "deadline",
                                        j.deadline(),
                                        "worker",
                                        j.worker(),
                                        "leaderEpoch",
                                        j.leaderEpoch()))
                .toList();
    }

    public TestResult test(Map<String, String> files, String testClass, Duration budget) {
        return test(files, testClass, budget, "standalone", "verification");
    }

    public synchronized TestResult test(
            Map<String, String> files,
            String testClass,
            Duration budget,
            String owner,
            String phase) {
        RepositoryWorkspace.validateFiles(files, testClass);
        reap();
        if (budget.isNegative() || budget.isZero())
            return failure("TIMEOUT", "No execution budget remains.");
        String digest = IntentCompiler.digest(new WorkbenchConfiguration().jsonMapper()
                .writeValueAsString(List.of(new TreeMap<>(files), testClass)));
        boolean resumable = store.all("repair-checkpoints", RepairService.Checkpoint.class).stream()
                .anyMatch(c -> c.runId().equals(owner));
        String id = resumable
                ? IntentCompiler.digest(new WorkbenchConfiguration().jsonMapper()
                        .writeValueAsString(List.of("test-step-v1", owner, phase)))
                : UUID.randomUUID().toString();
        var existing = jobs.get(id);
        if (existing != null && !existing.inputDigest().equals(digest))
            throw new PlatformException(409, "STEP_INPUT_CHANGED", "A recovered step must use its original immutable inputs.");
        if (existing == null) {
            if (jobs.values().stream().filter(this::active).count() >= capacity.remoteJobs())
                throw new PlatformException(429, "WORKER_QUEUE_FULL", "Execution queue is full.");
            while (jobs.size() >= capacity.jobHistory()) {
                var liveRuns = store.all("repair-runs", RepairRun.class).stream()
                        .filter(r -> Set.of("QUEUED", "RUNNING").contains(r.status()))
                        .map(RepairRun::id).collect(java.util.stream.Collectors.toSet());
                var old = jobs.values().stream()
                        .filter(j -> !active(j) && !awaitingResults.contains(j.id()) && !liveRuns.contains(j.owner()))
                        .findFirst().orElseThrow(() -> new PlatformException(
                                429, "WORKER_HISTORY_FULL", "Execution results are still being collected."));
                store.remove("worker-jobs", old.id());
                jobs.remove(old.id());
            }
            var deadline = clock.instant().plus(budget.compareTo(Duration.ofSeconds(120)) > 0
                    ? Duration.ofSeconds(120) : budget);
            save(new Job(id, owner, phase, digest, Map.copyOf(files), testClass, deadline,
                    "QUEUED", "", "", clock.instant(), 0, null, store.leaderEpoch()));
        }
        awaitingResults.add(id);
        long stop = System.nanoTime() + budget.toNanos();
        try {
            while (active(jobs.get(id))) {
                if (System.nanoTime() >= stop) {
                    var j = jobs.get(id);
                    save(
                            changed(
                                    j,
                                    "TIMEOUT",
                                    j.worker(),
                                    j.fence(),
                                    clock.instant(),
                                    j.deliveries(),
                                    failure("TIMEOUT", "Execution deadline exceeded.")));
                    break;
                }
                wait(250);
                reap();
            }
            return jobs.get(id).result();
        } catch (InterruptedException cancelled) {
            var j = jobs.get(id);
            if (active(j)) save(
                    changed(
                            j,
                            "CANCELLED",
                            j.worker(),
                            j.fence(),
                            clock.instant(),
                            j.deliveries(),
                            failure("CANCELLED", "Repair cancelled; worker lease revoked.")));
            Thread.currentThread().interrupt();
            throw new PlatformException(409, "RUN_CANCELLED", "Repair cancelled.");
        } finally {
            awaitingResults.remove(id);
        }
    }
}
