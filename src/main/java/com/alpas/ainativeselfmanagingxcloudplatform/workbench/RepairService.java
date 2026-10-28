package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Asynchronous bounded agent loop: inspect, test, propose, validate, test, submit for review. */
@Service
public class RepairService implements AutoCloseable {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PromptPolicies prompts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry metrics;

    private final RepositoryWorkspace repositories;
    private final SandboxExecutor sandbox;
    private final PatchPlanner model;
    private final RepairTasks tasks;
    private final StateStore store;
    private final AuditLog audit;
    private final Clock clock;
    private final IntentCompiler compiler;
    private final Map<String, RepairRun> runs = new ConcurrentHashMap<>();
    private final Set<String> cancellationRequests = ConcurrentHashMap.newKeySet();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, Comparison> comparisons = new ConcurrentHashMap<>();
    private volatile RepairPolicy active =
            new RepairPolicy("mutation-v1", RepairSearch.DEFAULT_ORDER, List.of());
    private final ExecutionCapacity capacity;
    private final ThreadPoolExecutor workers;

    public RepairService(
            RepositoryWorkspace repositories,
            SandboxExecutor sandbox,
            PatchPlanner model,
            RepairTasks tasks,
            StateStore store,
            AuditLog audit,
            Clock clock,
            IntentCompiler compiler) {
        this(repositories, sandbox, model, tasks, store, audit, clock, compiler,
                ExecutionCapacity.defaults());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RepairService(
            RepositoryWorkspace repositories,
            SandboxExecutor sandbox,
            PatchPlanner model,
            RepairTasks tasks,
            StateStore store,
            AuditLog audit,
            Clock clock,
            IntentCompiler compiler,
            ExecutionCapacity capacity) {
        this.capacity = capacity;
        workers = new ThreadPoolExecutor(
                capacity.repairWorkers(), capacity.repairWorkers(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity.repairQueue()),
                Thread.ofPlatform().daemon().name("repair-worker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.repositories = repositories;
        this.sandbox = sandbox;
        this.model = model;
        this.tasks = tasks;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.compiler = compiler;
        store.all("repair-policy", RepairPolicy.class).forEach(p -> active = p);
        for (var run : store.all("repair-runs", RepairRun.class)) {
            if (List.of("QUEUED", "RUNNING", "APPLYING").contains(run.status())) {
                boolean committed =
                        run.status().equals("APPLYING")
                                && repositories.matchesApprovedCommit(
                                        run.repositoryId(),
                                        run.baseCommit(),
                                        run.id(),
                                        run.digest());
                var e = new Execution(run);
                e.status = committed ? "APPROVED" : "INTERRUPTED";
                e.approvedCommit = committed ? repositories.head(run.repositoryId()) : null;
                e.detail =
                        committed
                                ? "Recovered the reviewed commit after restart."
                                : "The worker stopped before completion. No automatic replay; start"
                                        + " a new run.";
                e.completedAt = clock.instant();
                run = e.freeze();
                store.put("repair-runs", run.id(), run);
            }
            runs.put(run.id(), run);
            repositories.discardOrphan(run.repositoryId(), run.id());
        }
        for (var comparison : store.all("repair-comparisons", Comparison.class)) {
            if (List.of("QUEUED", "RUNNING").contains(comparison.status()))
                comparison =
                        new Comparison(
                                comparison.id(),
                                "INTERRUPTED",
                                comparison.createdAt(),
                                comparison.baseline(),
                                comparison.candidate(),
                                comparison.trials(),
                                comparison.safety(),
                                false,
                                "Evaluation interrupted; rerun before promotion.");
            comparisons.put(comparison.id(), comparison);
            store.put("repair-comparisons", comparison.id(), comparison);
        }
    }

    public record ModelCall(
            String runId,
            int sequence,
            InferenceUsage usage,
            long durationMs,
            String contextPolicy,
            List<String> paths,
            int contextBytes,
            Double costUsd,
            String pricing) {}

    public List<ModelCall> modelCalls(String id) {
        require(id);
        return store.all("model-calls", ModelCall.class).stream()
                .filter(c -> c.runId().equals(id))
                .sorted(Comparator.comparingInt(ModelCall::sequence))
                .toList();
    }

    @jakarta.annotation.PostConstruct
    void registerCapacityMetrics() {
        if (metrics == null) return;
        metrics.gauge("platform.repair.active", workers, ThreadPoolExecutor::getActiveCount);
        metrics.gauge("platform.repair.queued", workers, w -> w.getQueue().size());
        metrics.gauge("platform.repair.slots", workers, ThreadPoolExecutor::getCorePoolSize);
    }

    public Map<String, Object> capacity() {
        return Map.of(
                "limits", capacity,
                "activeRepairs", workers.getActiveCount(),
                "queuedRepairs", workers.getQueue().size(),
                "completedRepairs", workers.getCompletedTaskCount(),
                "coordinators", 1,
                "strategy", "One fenced coordinator with bounded execution workers");
    }

    public Map<String, Object> config() {
        return Map.of(
                "sandbox",
                sandbox.capabilities(),
                "providers",
                model.providers(),
                "activePolicy",
                active,
                "workers",
                capacity.repairWorkers(),
                "queueCapacity",
                capacity.repairQueue(),
                "maxModelCalls",
                3,
                "maxOutputTokensPerCall",
                4096,
                "maxInputBytesPerCall",
                40000,
                "maxAggregateTokens",
                160000,
                "contextPolicy",
                SourceContext.VERSION);
    }

    public List<RepairRun> list() {
        return runs.values().stream()
                .sorted(Comparator.comparing(RepairRun::createdAt).reversed())
                .toList();
    }

    public RepairRun require(String id) {
        var run = runs.get(id);
        if (run == null)
            throw new PlatformException(404, "REPAIR_NOT_FOUND", "Repair run not found.");
        return run;
    }

    public synchronized RepairRun submit(
            String repositoryId,
            String instruction,
            String provider,
            int maxAttempts,
            int timeoutSeconds) {
        return submitWithPolicy(
                repositoryId,
                instruction,
                provider,
                maxAttempts,
                timeoutSeconds,
                active,
                prompts == null ? null : prompts.active());
    }

    synchronized RepairRun submitWithPolicy(
            String repositoryId,
            String instruction,
            String provider,
            int maxAttempts,
            int timeoutSeconds,
            RepairPolicy selectedPolicy,
            PromptPolicies.Policy promptPolicy) {
        repositories.require(repositoryId);
        if (instruction == null
                || instruction.isBlank()
                || instruction.length() > 4000
                || !("mutation-search".equals(provider) || model.supports(String.valueOf(provider)))
                || maxAttempts < 1
                || maxAttempts > 12
                || timeoutSeconds < 20
                || timeoutSeconds > 120
                || (!"mutation-search".equals(provider) && maxAttempts > 3))
            throw new PlatformException(
                    400,
                    "INVALID_REPAIR_BUDGET",
                    "Provide a task, a configured provider, 1-12 attempts (model providers <=3),"
                            + " and a 20-120 second budget.");
        if (!"mutation-search".equals(provider) && !model.configured(provider))
            throw new PlatformException(
                    503,
                    "MODEL_NOT_CONFIGURED",
                    "Configure the selected model provider before starting a repair.");
        if (runs.size() >= 500)
            throw new PlatformException(
                    429, "REPAIR_CAPACITY", "At most 500 repair runs are retained per workspace.");
        if (!Boolean.TRUE.equals(sandbox.capabilities().get("available")))
            throw new PlatformException(
                    503,
                    "SANDBOX_UNAVAILABLE",
                    "Install Docker and pre-pull the configured Java 21 image. Source code is never"
                            + " executed on the host.");
        String id = UUID.randomUUID().toString();
        var policy = selectedPolicy;
        var run =
                new RepairRun(
                        id,
                        repositoryId,
                        repositories.head(repositoryId),
                        instruction,
                        provider,
                        !provider.equals("mutation-search") && promptPolicy != null
                                ? promptPolicy.version()
                                : policy.version(),
                        "QUEUED",
                        maxAttempts,
                        timeoutSeconds,
                        0,
                        clock.instant(),
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(),
                        "",
                        null,
                        null,
                        null,
                        "Queued for an isolated worker.");
        save(run);
        var future =
                new FutureTask<Void>(
                        () -> {
                            execute(run, policy, promptPolicy);
                            return null;
                        });
        futures.put(id, future);
        try {
            workers.execute(future);
        } catch (RejectedExecutionException full) {
            store.remove("repair-runs", id);
            runs.remove(id);
            futures.remove(id);
            throw new PlatformException(
                    429, "REPAIR_QUEUE_FULL", "Both workers and the bounded queue are occupied.");
        }
        audit.append(
                "REPAIR_QUEUED",
                "agent",
                repositoryId,
                id,
                provider + "; attempts=" + maxAttempts + "; deadline=" + timeoutSeconds + "s");
        return run;
    }

    private void execute(
            RepairRun initial, RepairPolicy policy, PromptPolicies.Policy promptPolicy) {
        var e = new Execution(initial);
        Path worktree = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(initial.timeoutSeconds());
        try {
            e.status = "RUNNING";
            e.detail = "Checking the baseline in an isolated test container.";
            save(e.freeze());
            worktree = repositories.worktree(e.repositoryId, e.id, e.baseCommit);
            var files = repositories.sources(worktree);
            Map<String, String> working = files;
            e.step(
                    "repository.inspect",
                    "PASS",
                    0,
                    "Detached worktree at "
                            + e.baseCommit
                            + "; "
                            + files.size()
                            + " immutable source/test inputs.");
            e.baseline =
                    sandbox.test(
                            files,
                            repositories.require(e.repositoryId).testClass(),
                            remaining(deadline),
                            e.id,
                            "baseline");
            e.step(
                    "sandbox.test.baseline",
                    e.baseline.status(),
                    e.baseline.durationMs(),
                    e.baseline.output());
            if (e.baseline.passed()) {
                e.status = "BASELINE_PASSED";
                e.detail = "The existing tests already pass. No patch is proposed.";
                return;
            }
            if (!e.baseline.status().equals("FAIL")
                    || e.baseline.timedOut()
                    || e.baseline.truncated())
                throw new PlatformException(
                        422,
                        "INVALID_BASELINE",
                        "Baseline timed out or exceeded the output budget; no repair search was"
                                + " attempted.");
            var candidates =
                    RepairSearch.candidates(files, policy.mutationOrder(), initial.maxAttempts());
            for (int i = 0; i < initial.maxAttempts(); i++) {
                remaining(deadline);
                if (Thread.currentThread().isInterrupted())
                    throw new PlatformException(409, "RUN_CANCELLED", "Cancelled.");
                List<Change> changes;
                String kind;
                if (!e.provider.equals("mutation-search")) {
                    var context =
                            SourceContext.select(
                                    working,
                                    e.instruction,
                                    e.verification == null
                                            ? e.baseline.output()
                                            : e.verification.output());
                    e.step(
                            "repository.search",
                            "PASS",
                            0,
                            "Policy "
                                    + SourceContext.VERSION
                                    + "; terms: "
                                    + String.join(", ", context.terms())
                                    + "; omitted paths: "
                                    + String.join(", ", context.omitted()));
                    e.step(
                            "repository.read",
                            "PASS",
                            0,
                            context.files().size()
                                    + " full files, "
                                    + context.bytes()
                                    + " bytes: "
                                    + String.join(", ", context.files().keySet()));
                    long callStart = System.nanoTime();
                    try {
                        changes =
                                model.propose(
                                        e.provider,
                                        prompts == null || promptPolicy == null
                                                ? e.instruction
                                                : prompts.instruction(promptPolicy, e.instruction),
                                        context.files(),
                                        e.verification == null ? e.baseline : e.verification,
                                        e.steps,
                                        remaining(deadline));
                    } finally {
                        var usage = model.usage();
                        if (usage != null)
                            store.put(
                                    "model-calls",
                                    e.id + "-" + (i + 1),
                                    new ModelCall(
                                            e.id,
                                            i + 1,
                                            usage,
                                            (System.nanoTime() - callStart) / 1_000_000,
                                            SourceContext.VERSION,
                                            List.copyOf(context.files().keySet()),
                                            context.bytes(),
                                            null,
                                            "unpriced"));
                    }
                    var calls = modelCalls(e.id);
                    long tokens =
                            calls.stream()
                                    .mapToLong(c -> Math.max(0, c.usage().totalTokens()))
                                    .sum();
                    e.step(
                            "model.propose",
                            "PASS",
                            (System.nanoTime() - callStart) / 1_000_000,
                            "Provider "
                                    + e.provider
                                    + "; requested model "
                                    + model.model(e.provider)
                                    + "; call "
                                    + (i + 1)
                                    + "/3; reported cumulative tokens "
                                    + tokens
                                    + " (missing usage remains unknown). Cost unpriced.");
                    if (tokens > 160000)
                        throw new PlatformException(
                                422,
                                "MODEL_TOKEN_BUDGET",
                                "Aggregate reported token budget exceeded; no further calls or"
                                        + " verification.");
                    kind = "GENERATIVE";
                } else {
                    if (i >= candidates.size()) break;
                    changes = candidates.get(i).changes();
                    kind = candidates.get(i).kind();
                }
                var candidate =
                        RepairSearch.validateAndApply(
                                e.provider.equals("mutation-search") ? files : working, changes);
                e.attempts++;
                e.step(
                        "patch.validate",
                        "PASS",
                        0,
                        "Attempt "
                                + e.attempts
                                + ": "
                                + kind
                                + "; "
                                + changes.size()
                                + " source file(s); tests and tool configuration unchanged.");
                e.verification =
                        sandbox.test(
                                candidate,
                                repositories.require(e.repositoryId).testClass(),
                                remaining(deadline),
                                e.id,
                                "attempt-" + e.attempts);
                e.step(
                        "sandbox.test.candidate",
                        e.verification.status(),
                        e.verification.durationMs(),
                        e.verification.output());
                if (e.verification.passed()) {
                    RepositoryWorkspace.writeFiles(worktree, candidate);
                    e.patch = repositories.diff(worktree);
                    e.changes =
                            files.entrySet().stream()
                                    .filter(
                                            f ->
                                                    f.getKey().startsWith("src/main/")
                                                            && !f.getValue()
                                                                    .equals(
                                                                            candidate.get(
                                                                                    f.getKey())))
                                    .map(
                                            f ->
                                                    new Change(
                                                            f.getKey(),
                                                            IntentCompiler.digest(f.getValue()),
                                                            candidate.get(f.getKey())))
                                    .toList();
                    e.mutationKind = kind;
                    e.digest =
                            IntentCompiler.digest(
                                    compiler.serialize(
                                            List.of(
                                                    "repair-v1",
                                                    e.id,
                                                    e.repositoryId,
                                                    e.baseCommit,
                                                    e.changes,
                                                    e.patch,
                                                    e.verification,
                                                    e.policyVersion)));
                    e.status = "AWAITING_REVIEW";
                    e.detail =
                            "The patch passes the unchanged regression tests. Review the diff and"
                                    + " digest before committing.";
                    return;
                }
                if (!e.provider.equals("mutation-search")) working = candidate;
            }
            e.status = "FAILED";
            e.detail =
                    "No passing patch was found within the candidate budget. Repository head is"
                            + " unchanged.";
        } catch (PlatformException failure) {
            e.status =
                    failure.code().equals("RUN_CANCELLED") || Thread.currentThread().isInterrupted()
                            ? "CANCELLED"
                            : failure.code().equals("REPAIR_DEADLINE")
                                    ? "BUDGET_EXHAUSTED"
                                    : "FAILED";
            e.detail = failure.code() + ": " + failure.getMessage();
            if (!failure.details().isEmpty())
                e.step("tool.error", failure.code(), 0, String.join("\n", failure.details()));
        } catch (RuntimeException failure) {
            e.status = "FAILED";
            e.detail = "The worker failed. Inspect the operation trace before starting a new run.";
        } finally {
            // Clear interruption only for cleanup; the cancelled task never proceeds to approval.
            boolean interrupted = Thread.interrupted();
            if (worktree != null)
                try {
                    repositories.discard(e.repositoryId, worktree);
                } catch (RuntimeException cleanup) {
                    e.detail += " Disposable worktree cleanup requires a restart.";
                }
            e.completedAt = clock.instant();
            synchronized (this) {
                if (cancellationRequests.remove(e.id)) {
                    e.status = "CANCELLED";
                    e.detail = "Cancelled by the operator; repository head is unchanged.";
                }
                save(e.freeze());
                futures.remove(e.id);
            }
            if (metrics != null) {
                metrics.counter("platform.repair.runs", "provider", e.provider, "status", e.status)
                        .increment();
                metrics.timer("platform.repair.duration", "provider", e.provider)
                        .record(Duration.between(initial.createdAt(), e.completedAt));
            }
            audit.append(
                    "REPAIR_COMPLETED",
                    "repair-worker",
                    e.repositoryId,
                    e.id,
                    e.status + "; tested candidates=" + e.attempts);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0)
            throw new PlatformException(
                    409, "REPAIR_DEADLINE", "The run exhausted its wall-clock budget.");
        return Duration.ofNanos(nanos);
    }

    public synchronized RepairRun cancel(String id) {
        var run = require(id);
        synchronized (repositories.lock(run.repositoryId())) {
            run = require(id);
            if (!List.of("QUEUED", "RUNNING").contains(run.status()))
                throw new PlatformException(
                        409, "RUN_TERMINAL", "Only a queued or running repair can be cancelled.");
            cancellationRequests.add(id);
            var future = futures.get(id);
            if (future != null) future.cancel(true);
            if (run.status().equals("QUEUED")) {
                var e = new Execution(run);
                e.status = "CANCELLED";
                e.completedAt = clock.instant();
                e.detail = "Cancelled before worker execution.";
                save(e.freeze());
                futures.remove(id);
                cancellationRequests.remove(id);
            }
            return require(id);
        }
    }

    public RepairRun approve(String id, String digest) {
        store.requireAvailable();
        var run = require(id);
        synchronized (repositories.lock(run.repositoryId())) {
            run = require(id);
            if (repositories.require(run.repositoryId()).origin().startsWith("benchmark:"))
                throw new PlatformException(
                        409,
                        "EVALUATION_READ_ONLY",
                        "Benchmark repairs cannot be approved or used as training feedback.");
            if (run.digest() == null || !run.digest().equals(digest))
                throw new PlatformException(
                        409,
                        "DIGEST_MISMATCH",
                        "Approval must match the exact tested patch digest.");
            if (run.status().equals("APPROVED")) return run;
            if (!run.status().equals("AWAITING_REVIEW"))
                throw new PlatformException(
                        409, "PATCH_NOT_REVIEWABLE", "Only a verified patch can be committed.");
            if (!repositories.head(run.repositoryId()).equals(run.baseCommit()))
                throw new PlatformException(
                        409,
                        "STALE_PATCH",
                        "Repository head changed. Start and review a fresh repair.");
            var e = new Execution(run);
            Path worktree = null;
            try {
                worktree =
                        repositories.worktree(
                                run.repositoryId(), "approval-" + run.id(), run.baseCommit());
                var files =
                        RepairSearch.validateAndApply(
                                repositories.sources(worktree), run.changes());
                RepositoryWorkspace.writeFiles(worktree, files);
                if (!repositories.diff(worktree).equals(run.patch()))
                    throw new PlatformException(
                            409,
                            "PATCH_CHANGED",
                            "The reconstructed patch differs from the tested diff.");
                e.status = "APPLYING";
                save(e.freeze()); // Durable marker precedes branch mutation.
                e.approvedCommit =
                        repositories.commit(
                                run.repositoryId(), worktree, run.baseCommit(), id, digest);
                e.status = "APPROVED";
                e.detail = "Reviewed patch committed to the managed repository.";
                save(e.freeze());
                audit.append(
                        "PATCH_APPROVED",
                        "local-operator",
                        run.repositoryId(),
                        id,
                        e.approvedCommit + "; digest=" + digest);
                return e.freeze();
            } finally {
                if (worktree != null) repositories.discard(run.repositoryId(), worktree);
            }
        }
    }

    private void save(RepairRun run) {
        store.put("repair-runs", run.id(), run);
        runs.put(run.id(), run);
    }

    private final class Execution {
        final String id, repositoryId, baseCommit, instruction, provider, policyVersion;
        final int maxAttempts, timeoutSeconds;
        final Instant createdAt;
        String status, patch, digest, mutationKind, approvedCommit, detail;
        int attempts;
        Instant completedAt;
        List<Step> steps;
        TestResult baseline, verification;
        List<Change> changes;

        Execution(RepairRun r) {
            id = r.id();
            repositoryId = r.repositoryId();
            baseCommit = r.baseCommit();
            instruction = r.instruction();
            provider = r.provider();
            policyVersion = r.policyVersion();
            maxAttempts = r.maxAttempts();
            timeoutSeconds = r.timeoutSeconds();
            createdAt = r.createdAt();
            status = r.status();
            patch = r.patch();
            digest = r.digest();
            mutationKind = r.mutationKind();
            approvedCommit = r.approvedCommit();
            detail = r.detail();
            attempts = r.attempts();
            completedAt = r.completedAt();
            steps = new ArrayList<>(r.steps());
            baseline = r.baseline();
            verification = r.verification();
            changes = r.changes();
        }

        void step(String tool, String outcome, long ms, String output) {
            steps.add(new Step(steps.size() + 1, tool, outcome, ms, output));
            save(freeze());
        }

        RepairRun freeze() {
            return new RepairRun(
                    id,
                    repositoryId,
                    baseCommit,
                    instruction,
                    provider,
                    policyVersion,
                    status,
                    maxAttempts,
                    timeoutSeconds,
                    attempts,
                    createdAt,
                    completedAt,
                    List.copyOf(steps),
                    baseline,
                    verification,
                    List.copyOf(changes),
                    patch,
                    digest,
                    mutationKind,
                    approvedCommit,
                    detail);
        }
    }

    public RepairPolicy activePolicy() {
        return active;
    }

    public RepairPolicy candidatePolicy() {
        var accepted =
                list().stream()
                        .filter(
                                r ->
                                        r.status().equals("APPROVED")
                                                && r.provider().equals("mutation-search")
                                                && repositories
                                                        .require(r.repositoryId())
                                                        .origin()
                                                        .startsWith("feedback-"))
                        .sorted(Comparator.comparing(RepairRun::id))
                        .toList();
        if (accepted.isEmpty())
            throw new PlatformException(
                    409,
                    "FEEDBACK_REQUIRED",
                    "Approve a catalog repair before deriving a candidate search policy.");
        var counts = new HashMap<String, Integer>();
        accepted.forEach(r -> counts.merge(r.mutationKind(), 1, Integer::sum));
        var order = new ArrayList<>(RepairSearch.DEFAULT_ORDER);
        order.sort(
                Comparator.comparingInt((String kind) -> counts.getOrDefault(kind, 0)).reversed());
        var ids = accepted.stream().map(RepairRun::id).toList();
        return new RepairPolicy(
                "feedback-"
                        + IntentCompiler.digest(compiler.serialize(List.of(order, ids)))
                                .substring(0, 12),
                List.copyOf(order),
                ids);
    }

    public synchronized Comparison compare() {
        if (comparisons.size() >= 20)
            throw new PlatformException(
                    429,
                    "COMPARISON_CAPACITY",
                    "At most 20 paired evaluation reports are retained.");
        if (comparisons.values().stream()
                .anyMatch(c -> List.of("QUEUED", "RUNNING").contains(c.status())))
            throw new PlatformException(
                    429, "COMPARISON_BUSY", "A comparison is already queued or running.");
        if (!Boolean.TRUE.equals(sandbox.capabilities().get("available")))
            throw new PlatformException(
                    503,
                    "SANDBOX_UNAVAILABLE",
                    "Docker and the Java 21 image are required for paired evaluation.");
        var comparison =
                new Comparison(
                        UUID.randomUUID().toString(),
                        "QUEUED",
                        clock.instant(),
                        active,
                        candidatePolicy(),
                        List.of(),
                        Map.of(),
                        false,
                        "Paired evaluation on a disjoint public smoke set; no live-model claims.");
        save(comparison);
        try {
            workers.execute(() -> evaluate(comparison));
        } catch (RejectedExecutionException full) {
            comparisons.remove(comparison.id());
            store.remove("repair-comparisons", comparison.id());
            throw new PlatformException(429, "REPAIR_QUEUE_FULL", "Worker queue is full.");
        }
        return comparison;
    }

    private void evaluate(Comparison c) {
        var trials = new ArrayList<Trial>();
        var safety = new TreeMap<String, Boolean>();
        String status = "COMPLETED",
                detail =
                        "Paired bounded mutation search on public tasks held separate from accepted"
                            + " feedback. Measures search efficiency, not general model quality.";
        save(
                new Comparison(
                        c.id(),
                        "RUNNING",
                        c.createdAt(),
                        c.baseline(),
                        c.candidate(),
                        List.of(),
                        Map.of(),
                        false,
                        detail));
        try {
            var example = tasks.holdout().getFirst();
            var before = example.files().get("src/test/java/RulesTest.java");
            try {
                RepairSearch.validateAndApply(
                        example.files(),
                        List.of(
                                new Change(
                                        "src/test/java/RulesTest.java",
                                        IntentCompiler.digest(before),
                                        "public class RulesTest {}")));
                safety.put("immutable-tests", false);
            } catch (PlatformException expected) {
                safety.put("immutable-tests", expected.code().equals("PATCH_SCOPE_DENIED"));
            }
            try {
                RepairSearch.validateAndApply(
                        example.files(), List.of(new Change("../escape.java", "bad", "bad")));
                safety.put("path-boundary", false);
            } catch (PlatformException expected) {
                safety.put("path-boundary", true);
            }
            safety.put(
                    "disjoint-task-ids",
                    tasks.holdout().stream()
                            .noneMatch(
                                    t ->
                                            tasks.examples().stream()
                                                    .anyMatch(e -> e.id().equals(t.id()))));
            // Real isolation probe runs in the same constrained container as every candidate.
            var isolation =
                    Map.of(
                            "src/main/java/Probe.java",
                            "public class Probe {}",
                            "src/test/java/ProbeTest.java",
                            "public class ProbeTest { public static void main(String[] args) throws"
                                + " Exception { if (System.getenv(\"GEMINI_API_KEY\") != null ||"
                                + " java.nio.file.Files.exists(java.nio.file.Path.of(\"/var/run/docker.sock\")))"
                                + " throw new AssertionError(); try {"
                                + " java.nio.file.Files.writeString(java.nio.file.Path.of(\"/workspace/src/main/java/Probe.java\"),\"tamper\");"
                                + " throw new AssertionError(); }"
                                + " catch(java.nio.file.AccessDeniedException expected) {} var"
                                + " interfaces=java.net.NetworkInterface.networkInterfaces().toList();"
                                + " if(interfaces.stream().anyMatch(i -> { try { return"
                                + " !i.isLoopback(); } catch(Exception e) { return true; }})) throw"
                                + " new AssertionError(); System.out.println(\"Isolation controls"
                                + " verified\"); }}");
            safety.put(
                    "container-isolation",
                    sandbox.test(isolation, "ProbeTest", Duration.ofSeconds(20)).passed());
            for (var task : tasks.holdout())
                for (var policy : List.of(c.baseline(), c.candidate())) {
                    long start = System.nanoTime();
                    String outcome = "FAILED", patchDigest = "";
                    int attempts = 0;
                    var baseline =
                            sandbox.test(task.files(), task.testClass(), Duration.ofSeconds(20));
                    if (baseline.passed())
                        throw new IllegalStateException("Holdout baseline unexpectedly passed");
                    for (var candidate :
                            RepairSearch.candidates(task.files(), policy.mutationOrder(), 12)) {
                        attempts++;
                        var files =
                                RepairSearch.validateAndApply(task.files(), candidate.changes());
                        if (sandbox.test(files, task.testClass(), Duration.ofSeconds(20))
                                .passed()) {
                            outcome = "PASS";
                            patchDigest =
                                    IntentCompiler.digest(compiler.serialize(candidate.changes()));
                            break;
                        }
                    }
                    trials.add(
                            new Trial(
                                    task.id(),
                                    policy == c.baseline() ? "baseline" : "candidate",
                                    policy.version(),
                                    outcome,
                                    attempts,
                                    (System.nanoTime() - start) / 1_000_000,
                                    patchDigest));
                    save(
                            new Comparison(
                                    c.id(),
                                    "RUNNING",
                                    c.createdAt(),
                                    c.baseline(),
                                    c.candidate(),
                                    List.copyOf(trials),
                                    Map.copyOf(safety),
                                    false,
                                    detail));
                }
        } catch (RuntimeException failure) {
            status = "FAILED";
            detail = "Paired evaluation failed before completion. No promotion is allowed.";
        }
        var baseline = trials.stream().filter(t -> t.variant().equals("baseline")).toList();
        var candidate = trials.stream().filter(t -> t.variant().equals("candidate")).toList();
        boolean promotable =
                status.equals("COMPLETED")
                        && baseline.size() == tasks.holdout().size()
                        && candidate.size() == baseline.size()
                        && safety.values().stream().allMatch(Boolean::booleanValue)
                        && safety.size() == 4
                        && baseline.stream().allMatch(t -> t.outcome().equals("PASS"))
                        && candidate.stream().allMatch(t -> t.outcome().equals("PASS"))
                        && candidate.stream().mapToInt(Trial::attempts).sum()
                                < baseline.stream().mapToInt(Trial::attempts).sum();
        save(
                new Comparison(
                        c.id(),
                        status,
                        c.createdAt(),
                        c.baseline(),
                        c.candidate(),
                        List.copyOf(trials),
                        Map.copyOf(safety),
                        promotable,
                        detail));
        audit.append(
                "REPAIR_COMPARISON_COMPLETED",
                "evaluation-harness",
                null,
                c.id(),
                status + "; promotable=" + promotable);
    }

    private void save(Comparison c) {
        store.put("repair-comparisons", c.id(), c);
        comparisons.put(c.id(), c);
    }

    public List<Comparison> comparisons() {
        return comparisons.values().stream()
                .sorted(Comparator.comparing(Comparison::createdAt).reversed())
                .toList();
    }

    public synchronized RepairPolicy promote(String id) {
        var c = comparisons.get(id);
        if (c == null)
            throw new PlatformException(404, "COMPARISON_NOT_FOUND", "Comparison not found.");
        if (active.version().equals(c.candidate().version()) && c.promotable()) return active;
        if (!c.promotable() || !c.baseline().version().equals(active.version()))
            throw new PlatformException(
                    409,
                    "PROMOTION_DENIED",
                    "Promotion requires completed safety checks, no task regression, fewer"
                            + " attempts, and the current baseline version.");
        store.put("repair-policy", "active", c.candidate());
        active = c.candidate();
        audit.append("REPAIR_POLICY_PROMOTED", "local-operator", null, id, active.version());
        return active;
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
