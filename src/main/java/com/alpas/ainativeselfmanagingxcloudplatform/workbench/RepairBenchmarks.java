package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Paired, counterbalanced public-corpus evaluations. Every score links to an actual repair run. */
@Service
public final class RepairBenchmarks {
    public record Request(
            String baselineProvider,
            String candidateProvider,
            String baselinePolicy,
            String candidatePolicy,
            int repetitions,
            int maxAttempts,
            int timeoutSeconds) {}

    public record Trial(
            String taskId,
            String category,
            int repetition,
            String variant,
            String provider,
            String requestedModel,
            String reportedModel,
            String runId,
            String outcome,
            int attempts,
            long durationMs,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            Double costUsd,
            String patchDigest,
            boolean scopeValid) {}

    public record Report(
            String id,
            String status,
            Instant createdAt,
            Instant completedAt,
            String datasetVersion,
            String datasetDigest,
            String harnessVersion,
            String sourceRevision,
            String contextPolicy,
            Request configuration,
            PromptPolicies.Policy baseline,
            PromptPolicies.Policy candidate,
            List<Trial> trials,
            Map<String, Boolean> safety,
            boolean promotable,
            String detail) {}

    private final RepairCorpus corpus;
    private final RepairService repairs;
    private final RepositoryWorkspace repositories;
    private final PromptPolicies policies;
    private final PatchPlanner models;
    private final SandboxExecutor sandbox;
    private final StateStore store;
    private final Clock clock;
    private final Map<String, Report> reports = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("paired-benchmark").factory());
    private Future<?> running;
    private volatile String currentRun;

    public RepairBenchmarks(
            RepairCorpus corpus,
            RepairService repairs,
            RepositoryWorkspace repositories,
            PromptPolicies policies,
            PatchPlanner models,
            SandboxExecutor sandbox,
            StateStore store,
            Clock clock) {
        this.corpus = corpus;
        this.repairs = repairs;
        this.repositories = repositories;
        this.policies = policies;
        this.models = models;
        this.sandbox = sandbox;
        this.store = store;
        this.clock = clock;
        for (var report : store.all("repair-benchmarks", Report.class)) {
            if (Set.of("QUEUED", "RUNNING").contains(report.status()))
                report =
                        copy(
                                report,
                                "INTERRUPTED",
                                report.trials(),
                                report.safety(),
                                false,
                                "Coordinator restarted; compare again before promotion.",
                                clock.instant());
            save(report);
        }
    }

    public List<Report> list() {
        return reports.values().stream()
                .sorted(Comparator.comparing(Report::createdAt).reversed())
                .toList();
    }

    public Report require(String id) {
        var r = reports.get(id);
        if (r == null)
            throw new PlatformException(404, "BENCHMARK_NOT_FOUND", "Benchmark not found.");
        return r;
    }

    public synchronized Report start(Request c) {
        if (running != null && !running.isDone())
            throw new PlatformException(
                    429, "BENCHMARK_BUSY", "One paired benchmark can run at a time.");
        if (c == null
                || !models.providers().contains(c.baselineProvider())
                || !models.providers().contains(c.candidateProvider())
                || c.repetitions() < 1
                || c.repetitions() > 3
                || c.maxAttempts() < 1
                || c.maxAttempts() > 3
                || c.timeoutSeconds() < 20
                || c.timeoutSeconds() > 120)
            throw new PlatformException(
                    400,
                    "BENCHMARK_BUDGET",
                    "Choose configured providers, 1–3 repetitions, 1–3 attempts and a 20–120 second"
                            + " budget per task/variant.");
        if (!Boolean.TRUE.equals(sandbox.capabilities().get("available")))
            throw new PlatformException(
                    503, "SANDBOX_UNAVAILABLE", "Connect an isolated worker before benchmarking.");
        if (reports.size() >= 10)
            throw new PlatformException(
                    429, "BENCHMARK_CAPACITY", "At most 10 benchmark reports are retained.");
        var base = policies.require(c.baselinePolicy());
        var candidate = policies.require(c.candidatePolicy());
        var report =
                new Report(
                        UUID.randomUUID().toString(),
                        "QUEUED",
                        clock.instant(),
                        null,
                        corpus.dataset().version(),
                        corpus.digest(),
                        "paired-repair-v1",
                        System.getenv().getOrDefault("RENDER_GIT_COMMIT", "local-unrecorded"),
                        SourceContext.VERSION,
                        c,
                        base,
                        candidate,
                        List.of(),
                        Map.of(),
                        false,
                        "Public synthetic evaluation split; not a blind industrial benchmark.");
        save(report);
        running = executor.submit(() -> execute(report));
        return report;
    }

    private void execute(Report initial) {
        var trials = new ArrayList<Trial>();
        var safety = new LinkedHashMap<String, Boolean>();
        try {
            save(
                    copy(
                            initial,
                            "RUNNING",
                            trials,
                            safety,
                            false,
                            "Checking runner isolation before paired trials.",
                            null));
            safety.put("container-isolation", isolation());
            if (!safety.get("container-isolation"))
                throw new PlatformException(
                        409, "ISOLATION_PROBE_FAILED", "The actual worker isolation probe failed.");
            int index = 0;
            for (var task : corpus.evaluation()) {
                var repository =
                        repositories.list().stream()
                                .filter(r -> r.origin().equals("benchmark:" + task.id()))
                                .findFirst()
                                .orElseGet(
                                        () ->
                                                repositories.create(
                                                        "Evaluation " + task.id(),
                                                        task.files(),
                                                        task.testClass(),
                                                        "benchmark:" + task.id()));
                if (!repositories.files(repository.id()).equals(task.files()))
                    throw new PlatformException(
                            409,
                            "CORPUS_CHANGED",
                            "An evaluation repository differs from its versioned inputs.");
                for (int repeat = 1; repeat <= initial.configuration().repetitions(); repeat++) {
                    var order =
                            (index + repeat) % 2 == 0
                                    ? List.of("baseline", "candidate")
                                    : List.of("candidate", "baseline");
                    for (String variant : order) {
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedException();
                        var policy =
                                variant.equals("baseline")
                                        ? initial.baseline()
                                        : initial.candidate();
                        String provider =
                                variant.equals("baseline")
                                        ? initial.configuration().baselineProvider()
                                        : initial.configuration().candidateProvider();
                        var queued =
                                repairs.submitWithPolicy(
                                        repository.id(),
                                        task.instruction(),
                                        provider,
                                        initial.configuration().maxAttempts(),
                                        initial.configuration().timeoutSeconds(),
                                        new RepairPolicy(
                                                policy.version(),
                                                policy.mutationOrder(),
                                                policy.trainingRunIds()),
                                        policy);
                        currentRun = queued.id();
                        RepairRun run;
                        do {
                            Thread.sleep(100);
                            run = repairs.require(queued.id());
                        } while (Set.of("QUEUED", "RUNNING").contains(run.status()));
                        currentRun = null;
                        var calls = repairs.modelCalls(run.id());
                        boolean known =
                                provider.equals("mutation-search")
                                        || (!calls.isEmpty()
                                                && calls.stream()
                                                        .allMatch(
                                                                c ->
                                                                        c.usage().inputTokens() >= 0
                                                                                && c.usage()
                                                                                                .outputTokens()
                                                                                        >= 0
                                                                                && c.usage()
                                                                                                .totalTokens()
                                                                                        >= 0));
                        long
                                input =
                                        known
                                                ? calls.stream()
                                                        .mapToLong(c -> c.usage().inputTokens())
                                                        .sum()
                                                : -1,
                                output =
                                        known
                                                ? calls.stream()
                                                        .mapToLong(c -> c.usage().outputTokens())
                                                        .sum()
                                                : -1,
                                total =
                                        known
                                                ? calls.stream()
                                                        .mapToLong(c -> c.usage().totalTokens())
                                                        .sum()
                                                : -1;
                        boolean passing =
                                run.status().equals("AWAITING_REVIEW")
                                        && run.baseline() != null
                                        && run.baseline().status().equals("FAIL")
                                        && run.verification() != null
                                        && run.verification().passed();
                        boolean scope =
                                run.changes().stream()
                                                .allMatch(
                                                        change ->
                                                                change.path()
                                                                        .startsWith(
                                                                                "src/main/java/"))
                                        && !run.patch().contains("diff --git a/src/test/");
                        trials.add(
                                new Trial(
                                        task.id(),
                                        task.category(),
                                        repeat,
                                        variant,
                                        provider,
                                        models.model(provider),
                                        provider.equals("mutation-search")
                                                ? "deterministic-reference"
                                                : calls.stream()
                                                        .map(c -> c.usage().reportedModel())
                                                        .distinct()
                                                        .sorted()
                                                        .collect(
                                                                java.util.stream.Collectors.joining(
                                                                        ",")),
                                        run.id(),
                                        passing ? "PASS" : run.status(),
                                        run.attempts(),
                                        Duration.between(run.createdAt(), run.completedAt())
                                                .toMillis(),
                                        input,
                                        output,
                                        total,
                                        null,
                                        run.digest(),
                                        scope));
                        save(
                                copy(
                                        initial,
                                        "RUNNING",
                                        trials,
                                        safety,
                                        false,
                                        trials.size()
                                                + " / "
                                                + (corpus.evaluation().size()
                                                        * 2
                                                        * initial.configuration().repetitions())
                                                + " paired trials recorded.",
                                        null));
                    }
                }
                index++;
            }
            safety.put("immutable-test-inputs", trials.stream().allMatch(Trial::scopeValid));
            safety.put(
                    "paired-completeness",
                    trials.size()
                            == corpus.evaluation().size()
                                    * 2
                                    * initial.configuration().repetitions());
            safety.put("no-pass-to-fail", noRegression(trials));
            safety.put(
                    "same-provider-model",
                    initial.configuration()
                                    .baselineProvider()
                                    .equals(initial.configuration().candidateProvider())
                            && trials.stream().map(Trial::requestedModel).distinct().count() == 1
                            && trials.stream().map(Trial::reportedModel).distinct().count() == 1
                            && trials.stream()
                                    .noneMatch(
                                            t ->
                                                    t.reportedModel().isBlank()
                                                            || t.reportedModel()
                                                                    .contains("unreported")
                                                            || t.reportedModel().contains(",")));
            safety.put(
                    "candidate-quality-floor",
                    trials.stream()
                                    .filter(
                                            t ->
                                                    t.variant().equals("candidate")
                                                            && t.outcome().equals("PASS"))
                                    .count()
                            >= .8
                                    * corpus.evaluation().size()
                                    * initial.configuration().repetitions());
            safety.put("usage-recorded", trials.stream().allMatch(t -> t.totalTokens() >= 0));
            boolean improve = improves(trials);
            safety.put("measured-improvement", improve);
            boolean gate =
                    safety.values().stream().allMatch(Boolean::booleanValue)
                            && !initial.baseline().version().equals(initial.candidate().version());
            save(
                    copy(
                            initial,
                            "COMPLETED",
                            trials,
                            safety,
                            gate,
                            gate
                                    ? "Candidate meets the paired quality, regression, scope and"
                                            + " efficiency gates. Explicit operator promotion is"
                                            + " required."
                                    : "Comparison complete. One or more promotion gates did not"
                                            + " pass; the active policy is unchanged.",
                            clock.instant()));
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            save(
                    copy(
                            initial,
                            "CANCELLED",
                            trials,
                            safety,
                            false,
                            "Benchmark cancelled; no policy was promoted.",
                            clock.instant()));
        } catch (PlatformException failure) {
            save(
                    copy(
                            initial,
                            "FAILED",
                            trials,
                            safety,
                            false,
                            failure.code() + ": " + failure.getMessage(),
                            clock.instant()));
        } catch (RuntimeException failure) {
            save(
                    copy(
                            initial,
                            "FAILED",
                            trials,
                            safety,
                            false,
                            "Benchmark stopped; completed trials are retained.",
                            clock.instant()));
        } finally {
            var id = currentRun;
            currentRun = null;
            if (id != null)
                try {
                    repairs.cancel(id);
                } catch (PlatformException ignored) {
                }
        }
    }

    static boolean noRegression(List<Trial> trials) {
        return trials.stream()
                .filter(t -> t.variant().equals("baseline") && t.outcome().equals("PASS"))
                .allMatch(
                        base ->
                                trials.stream()
                                        .anyMatch(
                                                c ->
                                                        c.variant().equals("candidate")
                                                                && c.taskId().equals(base.taskId())
                                                                && c.repetition()
                                                                        == base.repetition()
                                                                && c.outcome().equals("PASS")));
    }

    static boolean improves(List<Trial> trials) {
        var baseline = trials.stream().filter(t -> t.variant().equals("baseline")).toList();
        var candidate = trials.stream().filter(t -> t.variant().equals("candidate")).toList();
        long basePass = baseline.stream().filter(t -> t.outcome().equals("PASS")).count(),
                candidatePass = candidate.stream().filter(t -> t.outcome().equals("PASS")).count();
        long bt = baseline.stream().mapToLong(Trial::totalTokens).sum(),
                ct = candidate.stream().mapToLong(Trial::totalTokens).sum();
        return !baseline.isEmpty()
                && baseline.size() == candidate.size()
                && ct <= bt * 1.2
                && (candidatePass > basePass
                        || (candidatePass == basePass
                                && candidate.stream().mapToInt(Trial::attempts).sum()
                                        < baseline.stream().mapToInt(Trial::attempts).sum()));
    }

    private boolean isolation() {
        var files =
                Map.of(
                        "src/main/java/Probe.java",
                        "class Probe {}",
                        "src/test/java/ProbeTest.java",
                        "public class ProbeTest { public static void main(String[] a) throws"
                            + " Exception {"
                            + " if(ProcessHandle.current().info().user().orElse(\"root\").equals(\"root\")"
                            + " || java.nio.file.Files.exists(java.nio.file.Path.of(\"/var/run/docker.sock\")))throw"
                            + " new AssertionError(\"privilege boundary\"); for(String key:new"
                            + " String[]{\"GITHUB_TOKEN\",\"GEMINI_API_KEY\",\"NEBIUS_API_KEY\",\"PLATFORM_OPERATOR_TOKEN\",\"ACTIONS_ID_TOKEN_REQUEST_TOKEN\"})"
                            + " if(System.getenv(key)!=null)throw new AssertionError(\"credential"
                            + " leak\"); try(var socket=new"
                            + " java.net.Socket()){try{socket.connect(new"
                            + " java.net.InetSocketAddress(\"1.1.1.1\",443),500);throw new"
                            + " AssertionError(\"network access\");}catch(java.io.IOException"
                            + " expected){}} System.out.println(\"Isolation probe passed\"); } }");
        return sandbox.test(files, "ProbeTest", Duration.ofSeconds(30)).passed();
    }

    public synchronized Report cancel(String id) {
        var r = require(id);
        if (!Set.of("QUEUED", "RUNNING").contains(r.status()))
            throw new PlatformException(409, "BENCHMARK_TERMINAL", "Benchmark has already ended.");
        if (running != null) running.cancel(true);
        var cancelled =
                copy(
                        r,
                        "CANCELLED",
                        r.trials(),
                        r.safety(),
                        false,
                        "Cancelled by operator.",
                        clock.instant());
        save(cancelled);
        return cancelled;
    }

    public synchronized PromptPolicies.Policy promote(String id) {
        var r = require(id);
        if (!r.status().equals("COMPLETED")
                || !r.promotable()
                || !r.datasetDigest().equals(corpus.digest()))
            throw new PlatformException(
                    409,
                    "PROMOTION_GATE_FAILED",
                    "A complete passing comparison against this corpus version is required.");
        return policies.activate(r.candidate().version(), r.baseline().version());
    }

    private Report copy(
            Report r,
            String status,
            List<Trial> trials,
            Map<String, Boolean> safety,
            boolean gate,
            String detail,
            Instant completed) {
        return new Report(
                r.id(),
                status,
                r.createdAt(),
                completed,
                r.datasetVersion(),
                r.datasetDigest(),
                r.harnessVersion(),
                r.sourceRevision(),
                r.contextPolicy(),
                r.configuration(),
                r.baseline(),
                r.candidate(),
                List.copyOf(trials),
                Map.copyOf(safety),
                gate,
                detail);
    }

    private void save(Report r) {
        store.put("repair-benchmarks", r.id(), r);
        reports.put(r.id(), r);
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
