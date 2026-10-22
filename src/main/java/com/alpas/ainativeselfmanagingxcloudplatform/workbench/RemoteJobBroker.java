package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/**
 * Single-coordinator queue. Lease tokens fence late results; interrupted jobs are never replayed.
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
            TestResult result) {}

    public record Lease(
            String id,
            String fence,
            String inputDigest,
            Map<String, String> files,
            String testClass,
            Instant deadline) {}

    public record Completion(String fence, String inputDigest, TestResult result) {}

    public record Worker(String id, Instant seenAt, String image) {}

    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Worker> workers = new HashMap<>();
    private final StateStore store;
    private final Clock clock;

    public RemoteJobBroker(StateStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        for (var job : store.all("worker-jobs", Job.class)) {
            if (active(job))
                job =
                        changed(
                                job,
                                "INTERRUPTED",
                                "",
                                "",
                                clock.instant(),
                                job.deliveries(),
                                failure(
                                        "INTERRUPTED",
                                        "Coordinator restarted; submit a new repair."));
            jobs.put(job.id(), job);
            store.put("worker-jobs", job.id(), job);
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
                result);
    }

    private static boolean activeStatus(String s) {
        return s.equals("QUEUED") || s.equals("LEASED");
    }

    private void save(Job j) {
        store.put("worker-jobs", j.id(), j);
        jobs.put(j.id(), j);
        notifyAll();
    }

    private void reap() {
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
        workers.values().removeIf(w -> w.seenAt().isBefore(now.minusSeconds(60)));
    }

    public synchronized void register(String worker, String image) {
        reap();
        if (image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]{0,200}"))
            throw new PlatformException(
                    400, "INVALID_WORKER", "Provide the preloaded sandbox image.");
        if (!workers.containsKey(worker) && workers.size() >= 4)
            throw new PlatformException(429, "WORKER_LIMIT", "At most four workers can register.");
        workers.put(worker, new Worker(worker, clock.instant(), image));
    }

    public synchronized Lease claim(String worker, String image) {
        register(worker, image);
        if (jobs.values().stream()
                .anyMatch(j -> j.status().equals("LEASED") && j.worker().equals(worker)))
            return null;
        for (var j : jobs.values())
            if (j.status().equals("QUEUED")
                    && (!j.files().containsKey("pom.xml")
                            || image.equals("xcloud-java21-runner:1"))) {
                var leased =
                        changed(
                                j,
                                "LEASED",
                                worker,
                                UUID.randomUUID().toString(),
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
        workers.computeIfPresent(worker, (k, w) -> new Worker(k, clock.instant(), w.image()));
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
        if (j == null || !j.worker().equals(worker) || !j.fence().equals(fence))
            throw new PlatformException(
                    409, "STALE_LEASE", "Worker identity or lease token is stale.");
        return j;
    }

    public synchronized Map<String, Object> capabilities() {
        reap();
        var online =
                workers.values().stream()
                        .filter(w -> w.seenAt().isAfter(clock.instant().minusSeconds(20)))
                        .toList();
        return Map.of(
                "available",
                !online.isEmpty(),
                "mode",
                "remote",
                "network",
                "none",
                "hostMounts",
                false,
                "workers",
                online,
                "queued",
                jobs.values().stream().filter(j -> j.status().equals("QUEUED")).count(),
                "detail",
                "Authenticated Docker workers; 20-second fenced leases, two deliveries, bounded"
                        + " queue.");
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
                                        j.worker()))
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
        if (jobs.values().stream().filter(this::active).count() >= 16)
            throw new PlatformException(429, "WORKER_QUEUE_FULL", "Execution queue is full.");
        while (jobs.size() >= 200) {
            var old = jobs.values().stream().filter(j -> !active(j)).findFirst().orElseThrow();
            jobs.remove(old.id());
            store.remove("worker-jobs", old.id());
        }
        String id = UUID.randomUUID().toString();
        String digest =
                IntentCompiler.digest(
                        new WorkbenchConfiguration()
                                .jsonMapper()
                                .writeValueAsString(List.of(new TreeMap<>(files), testClass)));
        var deadline =
                clock.instant()
                        .plus(
                                budget.compareTo(Duration.ofSeconds(120)) > 0
                                        ? Duration.ofSeconds(120)
                                        : budget);
        save(
                new Job(
                        id,
                        owner,
                        phase,
                        digest,
                        Map.copyOf(files),
                        testClass,
                        deadline,
                        "QUEUED",
                        "",
                        "",
                        clock.instant(),
                        0,
                        null));
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
            save(
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
        }
    }
}
