package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Durable isolated-test jobs. Database ownership and per-delivery fences guard every transition. */
@Component
public final class RemoteJobBroker implements SandboxExecutor {
    public record Job(
            String id, String owner, String phase, String inputDigest,
            Map<String, String> files, String testClass, Instant deadline, String status,
            String worker, String fence, Instant leaseUntil, int deliveries,
            TestResult result, long leaderEpoch) {}

    public record Lease(
            String id, String fence, String inputDigest, Map<String, String> files,
            String testClass, Instant deadline, long leaderEpoch) {}

    public record Completion(
            String fence, String inputDigest, TestResult result, long leaderEpoch) {}

    public record Worker(String id, Instant seenAt, String image, Instant acceptingUntil) {}

    private final Map<String, Worker> workers = new HashMap<>();
    private final Map<String, Integer> awaitingResults = new HashMap<>();
    private final ExecutionCapacity capacity;
    private final StateStore store;
    private final Clock clock;
    private final long epoch;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry metrics;

    public RemoteJobBroker(StateStore store, Clock clock) {
        this(store, clock, ExecutionCapacity.defaults());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RemoteJobBroker(StateStore store, Clock clock, ExecutionCapacity capacity) {
        this.capacity = capacity;
        this.store = store;
        this.clock = clock;
        this.epoch = store.leaderEpoch();
        transact(q -> null); // Recover active jobs atomically under the new ownership generation.
    }

    private static boolean active(Job job) {
        return job.status().equals("QUEUED") || job.status().equals("LEASED");
    }

    private static TestResult failure(String status, String detail) {
        return new TestResult(status, -1, status.equals("TIMEOUT"), false, 0, detail);
    }

    private Job changed(Job j, String status, String worker, String fence,
            Instant lease, int deliveries, TestResult result) {
        return new Job(j.id(), j.owner(), j.phase(), j.inputDigest(),
                Set.of("QUEUED", "LEASED").contains(status) ? j.files() : Map.of(),
                j.testClass(), j.deadline(), status, worker, fence, lease, deliveries, result, epoch);
    }

    private final class QueueTransaction {
        final StateStore.Transaction tx;
        final Map<String, Worker> registered = new HashMap<>(workers);
        final List<String> transitions = new ArrayList<>();
        QueueTransaction(StateStore.Transaction tx) { this.tx = tx; }
        List<Job> all() {
            var jobs = tx.all("worker-jobs", Job.class);
            jobs.sort(Comparator.comparing(Job::deadline).thenComparing(Job::id));
            return jobs;
        }
        Job get(String id) { return tx.get("worker-jobs", id, Job.class); }
        Job require(String id) {
            var job = get(id);
            if (job == null)
                throw new PlatformException(404, "JOB_NOT_FOUND", "Execution job not found.");
            return job;
        }
        void save(Job job) {
            var before = get(job.id());
            tx.put("worker-jobs", job.id(), job);
            if (before == null || !before.status().equals(job.status())) transitions.add(job.status());
        }
    }

    /** Publish local bookkeeping/metrics only after the durable transaction commits. */
    private <T> T transact(Function<QueueTransaction, T> operation) {
        var committed = new ArrayList<QueueTransaction>(1);
        T result = store.transaction(epoch, tx -> {
            var q = new QueueTransaction(tx);
            reap(q);
            T value = operation.apply(q);
            committed.add(q);
            return value;
        });
        var q = committed.getFirst();
        workers.clear();
        workers.putAll(q.registered);
        if (metrics != null)
            q.transitions.forEach(state -> metrics.counter("platform.worker.jobs", "state", state).increment());
        // Constructors do not hold the monitor, and have no waiters yet.
        if (!q.transitions.isEmpty() && Thread.holdsLock(this)) notifyAll();
        return result;
    }

    private void reap(QueueTransaction q) {
        var now = clock.instant();
        for (var j : q.all()) {
            if (!active(j)) continue;
            if (!now.isBefore(j.deadline())) {
                q.save(changed(j, "TIMEOUT", j.worker(), j.fence(), now, j.deliveries(),
                        failure("TIMEOUT", "Execution deadline exceeded.")));
            } else if (j.status().equals("LEASED")
                    && (j.leaderEpoch() != epoch || !now.isBefore(j.leaseUntil()))) {
                boolean retry = j.deliveries() < 2;
                q.save(changed(j, retry ? "QUEUED" : "WORKER_LOST", "", "", now,
                        j.deliveries(), retry ? null : failure("WORKER_LOST",
                                "Execution delivery limit reached after lease loss or coordinator replacement.")));
            } else if (j.leaderEpoch() != epoch) {
                q.save(changed(j, "QUEUED", "", "", now, j.deliveries(), null));
            }
        }
        q.registered.values().removeIf(w -> w.seenAt().isBefore(now.minusSeconds(60))
                || !now.isBefore(w.acceptingUntil()));
    }

    private void register(QueueTransaction q, String worker, String image, Instant acceptingUntil) {
        if (worker == null || worker.isBlank() || worker.length() > 300
                || image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]{0,200}"))
            throw new PlatformException(400, "INVALID_WORKER", "Provide a worker identity and preloaded sandbox image.");
        if (!q.registered.containsKey(worker) && q.registered.size() >= capacity.remoteWorkers())
            throw new PlatformException(429, "WORKER_LIMIT", "The configured worker limit is reached.");
        q.registered.put(worker, new Worker(worker, clock.instant(), image, acceptingUntil));
    }

    public synchronized void register(String worker, String image) {
        transact(q -> { register(q, worker, image, Instant.MAX); return null; });
    }

    public synchronized Lease claim(String worker, String image) {
        return claim(worker, image, Instant.MAX);
    }

    public synchronized Lease claim(String worker, String image, Instant acceptingUntil) {
        if (acceptingUntil == null || (!acceptingUntil.equals(Instant.MAX)
                && acceptingUntil.isAfter(clock.instant().plusSeconds(18300))))
            throw new PlatformException(400, "INVALID_WORKER", "Invalid worker acceptance window.");
        return transact(q -> {
            register(q, worker, image, acceptingUntil);
            var jobs = q.all();
            if (jobs.stream().anyMatch(j -> j.status().equals("LEASED") && j.worker().equals(worker)))
                return null;
            for (var j : jobs) {
                if (j.status().equals("QUEUED") && !j.deadline().isAfter(acceptingUntil)
                        && (!j.files().containsKey("pom.xml") || image.equals("xcloud-java21-runner:1"))) {
                    var leased = changed(j, "LEASED", worker, UUID.randomUUID().toString(),
                            clock.instant().plusSeconds(20), j.deliveries() + 1, null);
                    q.save(leased);
                    return new Lease(j.id(), leased.fence(), j.inputDigest(), j.files(),
                            j.testClass(), j.deadline(), epoch);
                }
            }
            return null;
        });
    }

    private Job authorized(QueueTransaction q, String id, String worker, String fence, long suppliedEpoch) {
        var j = q.get(id);
        if (j == null || suppliedEpoch != epoch || j.leaderEpoch() != suppliedEpoch
                || !j.worker().equals(worker) || !j.fence().equals(fence))
            throw new PlatformException(409, "STALE_LEASE", "Worker identity, coordinator epoch or lease token is stale.");
        return j;
    }

    public synchronized void heartbeat(String id, String worker, String fence, long suppliedEpoch) {
        transact(q -> {
            var j = authorized(q, id, worker, fence, suppliedEpoch);
            if (!j.status().equals("LEASED"))
                throw new PlatformException(409, "LEASE_ENDED", "This execution lease has ended.");
            q.registered.computeIfPresent(worker,
                    (k, w) -> new Worker(k, clock.instant(), w.image(), w.acceptingUntil()));
            q.save(changed(j, j.status(), worker, fence, clock.instant().plusSeconds(20), j.deliveries(), null));
            return null;
        });
    }

    public synchronized void complete(String id, String worker, Completion c) {
        transact(q -> {
            if (c == null) throw new PlatformException(400, "INVALID_RESULT", "Provide an execution result.");
            var j = authorized(q, id, worker, c.fence(), c.leaderEpoch());
            if (!j.inputDigest().equals(c.inputDigest()))
                throw new PlatformException(409, "INPUT_MISMATCH", "The result belongs to different inputs.");
            var r = c.result();
            if (r == null || r.status() == null || r.output() == null || r.output().length() > 65536
                    || r.durationMs() < 0 || r.durationMs() > 180000
                    || !Set.of("PASS", "FAIL", "TIMEOUT", "ERROR").contains(r.status())
                    || (r.passed() && (r.exitCode() != 0 || r.timedOut() || r.truncated())))
                throw new PlatformException(400, "INVALID_RESULT", "Worker result violates the execution contract.");
            if (j.status().equals("COMPLETED") && Objects.equals(j.result(), r)) return null;
            if (!j.status().equals("LEASED"))
                throw new PlatformException(409, "LEASE_ENDED", "This execution lease has ended.");
            q.save(changed(j, "COMPLETED", worker, c.fence(), clock.instant(), j.deliveries(), r));
            return null;
        });
    }

    /** Stable owner/phase identity makes uncertain submission retries reattach within retained history without renewing the budget. */
    public synchronized String submit(Map<String, String> files, String testClass, Duration budget,
            String owner, String phase) {
        RepositoryWorkspace.validateFiles(files, testClass);
        if (owner == null || owner.isBlank() || owner.length() > 120
                || phase == null || phase.isBlank() || phase.length() > 80
                || budget == null || budget.isNegative() || budget.isZero())
            throw new PlatformException(400, "INVALID_JOB", "Provide a stable owner, phase and positive execution budget.");
        var json = new WorkbenchConfiguration().jsonMapper();
        String id = IntentCompiler.digest(json.writeValueAsString(List.of(owner, phase)));
        String digest = IntentCompiler.digest(json.writeValueAsString(List.of(new TreeMap<>(files), testClass)));
        return transact(q -> {
            var jobs = q.all();
            // Pre-epoch jobs used random IDs. Reattach to their original receipt without
            // rewriting a worker-visible ID or silently executing the same phase twice.
            var matches = jobs.stream().filter(j -> j.owner().equals(owner) && j.phase().equals(phase)).toList();
            if (matches.size() > 1)
                throw new PlatformException(409, "JOB_ID_CONFLICT", "Multiple retained jobs identify this owner and phase; inspect their receipts.");
            var existing = matches.isEmpty() ? q.get(id) : matches.getFirst();
            if (existing != null) {
                if (!existing.inputDigest().equals(digest))
                    throw new PlatformException(409, "JOB_INPUT_CHANGED", "This owner and phase already identify different immutable inputs.");
                return existing.id();
            }
            if (jobs.stream().filter(RemoteJobBroker::active).count() >= capacity.remoteJobs())
                throw new PlatformException(429, "WORKER_QUEUE_FULL", "Execution queue is full.");
            while (jobs.size() >= capacity.jobHistory()) {
                var old = jobs.stream().filter(j -> !active(j) && !awaitingResults.containsKey(j.id()))
                        .findFirst().orElseThrow(() -> new PlatformException(429, "WORKER_HISTORY_FULL",
                                "Execution results are still being collected."));
                q.tx.remove("worker-jobs", old.id());
                jobs.remove(old);
            }
            var deadline = clock.instant().plus(budget.compareTo(Duration.ofSeconds(120)) > 0
                    ? Duration.ofSeconds(120) : budget);
            q.save(new Job(id, owner, phase, digest, Map.copyOf(files), testClass, deadline,
                    "QUEUED", "", "", clock.instant(), 0, null, epoch));
            return id;
        });
    }

    /** A caller may reattach after a process restart; completed receipts never dispatch a new job. */
    public synchronized TestResult await(String id) {
        var initial = transact(q -> q.require(id));
        long remaining = Math.max(0, Duration.between(clock.instant(), initial.deadline()).toNanos());
        long stop = System.nanoTime() + remaining;
        awaitingResults.merge(id, 1, Integer::sum);
        try {
            while (true) {
                var job = transact(q -> q.require(id));
                if (!active(job)) return job.result();
                if (System.nanoTime() >= stop) {
                    return transact(q -> {
                        var current = q.require(id);
                        if (!active(current)) return current.result();
                        var timeout = failure("TIMEOUT", "Execution deadline exceeded.");
                        q.save(changed(current, "TIMEOUT", current.worker(), current.fence(),
                                clock.instant(), current.deliveries(), timeout));
                        return timeout;
                    });
                }
                wait(250);
            }
        } catch (InterruptedException cancelled) {
            try {
                transact(q -> {
                    var j = q.require(id);
                    if (active(j)) q.save(changed(j, "CANCELLED", j.worker(), j.fence(),
                            clock.instant(), j.deliveries(), failure("CANCELLED", "Repair cancelled; worker lease revoked.")));
                    return null;
                });
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new PlatformException(409, "RUN_CANCELLED", "Repair cancelled.");
        } finally {
            awaitingResults.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1);
        }
    }

    public TestResult test(Map<String, String> files, String testClass, Duration budget) {
        return test(files, testClass, budget, UUID.randomUUID().toString(), "verification");
    }

    public synchronized TestResult test(Map<String, String> files, String testClass, Duration budget,
            String owner, String phase) {
        if (budget.isNegative() || budget.isZero()) return failure("TIMEOUT", "No execution budget remains.");
        return await(submit(files, testClass, budget, owner, phase));
    }

    private Map<String, Object> summary(Job j) {
        return Map.of("id", j.id(), "owner", j.owner(), "phase", j.phase(),
                "inputDigest", j.inputDigest(), "status", j.status(), "deliveries", j.deliveries(),
                "deadline", j.deadline(), "worker", j.worker(), "leaderEpoch", j.leaderEpoch());
    }

    public synchronized Map<String, Object> receipt(String id) {
        return transact(q -> {
            var j = q.require(id);
            var result = new LinkedHashMap<String, Object>(summary(j));
            result.put("result", j.result());
            return result;
        });
    }

    public synchronized List<Map<String, Object>> summary() {
        return transact(q -> q.all().stream().map(this::summary).toList());
    }

    public synchronized Map<String, Object> capabilities() {
        return transact(q -> {
            var now = clock.instant();
            var online = q.registered.values().stream()
                    .filter(w -> w.seenAt().isAfter(now.minusSeconds(20)))
                    .filter(w -> w.acceptingUntil().isAfter(now.plusSeconds(20))).toList();
            var jobs = q.all();
            var result = new LinkedHashMap<String, Object>();
            result.put("available", !online.isEmpty());
            result.put("mode", "remote");
            result.put("network", "none");
            result.put("hostMounts", false);
            result.put("workers", online);
            result.put("maxWorkers", capacity.remoteWorkers());
            result.put("maxInFlight", capacity.remoteJobs());
            result.put("queued", jobs.stream().filter(j -> j.status().equals("QUEUED")).count());
            result.put("leased", jobs.stream().filter(j -> j.status().equals("LEASED")).count());
            result.put("retainedJobs", jobs.size());
            result.put("leaderEpoch", epoch);
            result.put("detail", "Durable isolated-test jobs; epoch-fenced 20-second leases, two deliveries, original deadlines.");
            return result;
        });
    }
}
