package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class EvaluationService {
    private final ScenarioCatalog catalog;
    private final IntentCompiler compiler;
    private final IntentInterpreter interpreter;
    private final FeedbackStore feedback;
    private final AgentService agents;
    private final Clock clock;
    private final AuditLog audit;
    private final MeterRegistry metrics;
    private final Semaphore slot = new Semaphore(1);
    private final ArrayDeque<EvaluationReport> history = new ArrayDeque<>();

    private final StateStore store;

    public record History(List<EvaluationReport> reports) {}

    public EvaluationService(
            ScenarioCatalog catalog,
            IntentCompiler compiler,
            IntentInterpreter interpreter,
            FeedbackStore feedback,
            AgentService agents,
            Clock clock,
            AuditLog audit,
            MeterRegistry metrics) {
        this(
                catalog,
                compiler,
                interpreter,
                feedback,
                agents,
                clock,
                audit,
                metrics,
                StateStore.volatileStore());
    }

    @Autowired
    public EvaluationService(
            ScenarioCatalog catalog,
            IntentCompiler compiler,
            IntentInterpreter interpreter,
            FeedbackStore feedback,
            AgentService agents,
            Clock clock,
            AuditLog audit,
            MeterRegistry metrics,
            StateStore store) {
        this.catalog = catalog;
        this.compiler = compiler;
        this.interpreter = interpreter;
        this.feedback = feedback;
        this.agents = agents;
        this.clock = clock;
        this.audit = audit;
        this.metrics = metrics;
        this.store = store;
        store.all("evaluations", History.class).forEach(h -> history.addAll(h.reports()));
    }

    public EvaluationReport evaluate(String requestedMode) {
        if (!List.of("control-plane", "interpreter").contains(String.valueOf(requestedMode)))
            throw new PlatformException(
                    400, "INVALID_EVALUATION_MODE", "Choose control-plane or interpreter.");
        if (!slot.tryAcquire())
            throw new PlatformException(
                    429, "EVALUATION_BUSY", "An evaluation is already running.");
        try {
            return runSuite(requestedMode);
        } finally {
            slot.release();
        }
    }

    private EvaluationReport runSuite(String mode) {
        var cases = new ArrayList<EvaluationCase>();
        var regressions = feedback.list();
        var scenarios =
                catalog.all().stream()
                        .filter(
                                s ->
                                        "control-plane".equals(mode)
                                                ? s.spec() != null
                                                : s.fixture()
                                                        || ("gemini".equals(interpreter.provider())
                                                                && !"validation"
                                                                        .equals(s.category())))
                        .toList();
        for (var scenario : scenarios)
            cases.add(
                    check(
                            scenario.id(),
                            scenario.name(),
                            scenario.category(),
                            scenario.prompt(),
                            scenario.spec(),
                            scenario.expected(),
                            mode));
        for (var regression : regressions) {
            var run = agents.requireRun(regression.runId());
            cases.add(
                    check(
                            regression.id(),
                            regression.note(),
                            "feedback-regression",
                            run.prompt(),
                            regression.expectedSpec(),
                            regression.expectedDecision(),
                            mode));
        }
        if ("control-plane".equals(mode)) addSafetyProbes(cases);
        int passed = (int) cases.stream().filter(EvaluationCase::passed).count();
        var times = cases.stream().mapToDouble(EvaluationCase::latencyMs).sorted().toArray();
        String evidence =
                "control-plane".equals(mode)
                        ? "deterministic-control-plane"
                        : interpreter.provider() + "-interpreter";
        var result =
                new EvaluationReport(
                        UUID.randomUUID().toString(),
                        clock.instant(),
                        evidence,
                        IntentCompiler.digest(
                                compiler.serialize(
                                        List.of(
                                                scenarios,
                                                regressions,
                                                PolicyEngine.VERSION,
                                                "harness-v1"))),
                        passed,
                        cases.size(),
                        Math.round(10000.0 * passed / cases.size()) / 100.0,
                        percentile(times, .5),
                        percentile(times, .95),
                        regressions.size(),
                        cases,
                        "control-plane".equals(mode)
                                ? "Local compiler/policy and isolated simulator checks. Latency"
                                      + " excludes model and cloud calls; this is not a load"
                                      + " benchmark."
                                : "fixture".equals(interpreter.provider())
                                        ? "Exact curated-example replay, not a model-quality or"
                                              + " self-improvement score."
                                        : "Live Gemini smoke evaluation on a small public suite;"
                                              + " not a blind benchmark or evidence of autonomous"
                                              + " learning.");
        synchronized (history) {
            history.addFirst(result);
            if (history.size() > 20) history.removeLast();
            store.put("evaluations", "history", new History(List.copyOf(history)));
        }
        audit.append(
                "EVALUATION_COMPLETED",
                "evaluation-harness",
                null,
                result.id(),
                evidence + ": " + passed + "/" + cases.size());
        metrics.counter("platform.evaluation.cases", "mode", evidence, "result", "passed")
                .increment(passed);
        metrics.counter("platform.evaluation.cases", "mode", evidence, "result", "failed")
                .increment(cases.size() - passed);
        return result;
    }

    private EvaluationCase check(
            String id,
            String name,
            String category,
            String prompt,
            IntentSpec expectedSpec,
            String expected,
            String mode) {
        long start = System.nanoTime();
        String actual;
        String detail = "Matched expected outcome.";
        boolean exact = true;
        try {
            IntentSpec input =
                    "interpreter".equals(mode) ? interpreter.interpret(prompt) : expectedSpec;
            if ("interpreter".equals(mode) && !input.equals(expectedSpec)) {
                exact = false;
                detail =
                        "Intent differs from the golden specification, even if the policy outcome"
                            + " matches.";
            }
            actual = compiler.analyze(input).decision();
        } catch (PlatformException failure) {
            actual =
                    switch (failure.code()) {
                        case "CLARIFICATION_REQUIRED" -> "CLARIFY";
                        case "INVALID_INTENT" -> "INVALID";
                        default -> failure.code();
                    };
            detail = exact ? failure.getMessage() : detail + " " + failure.getMessage();
        }
        return new EvaluationCase(
                id,
                name,
                category,
                expected,
                actual,
                expected.equals(actual) && exact,
                (System.nanoTime() - start) / 1_000_000.0,
                detail);
    }

    private void addSafetyProbes(List<EvaluationCase> cases) {
        probe(
                cases,
                "digest-binding",
                "Approval is bound to the exact plan",
                control -> {
                    var plan = control.propose(safe());
                    expect(
                            "DIGEST_MISMATCH",
                            () -> control.apply(plan.id(), "0".repeat(64), "tampered-key"));
                    require(control.environments().isEmpty(), "Tampered approval mutated state");
                });
        probe(
                cases,
                "denied-execution",
                "Denied proposals cannot execute",
                control -> {
                    var plan = control.propose(safe().withRestrictedEgress(false));
                    expect(
                            "POLICY_DENIED",
                            () -> control.apply(plan.id(), plan.digest(), "denied-key"));
                    require(control.environments().isEmpty(), "Denied plan mutated state");
                });
        probe(
                cases,
                "retry-deduplication",
                "Retries execute only once",
                control -> {
                    var plan = control.propose(safe());
                    var first = control.apply(plan.id(), plan.digest(), "replay-key");
                    var second = control.apply(plan.id(), plan.digest(), "replay-key");
                    require(
                            second.replayed() && first.receipt().equals(second.receipt()),
                            "Retry was not deduplicated");
                    require(
                            control.environment(plan.environmentId()).desiredVersion() == 1,
                            "Retry changed desired version");
                });
        probe(
                cases,
                "stale-proposal",
                "Concurrent proposals invalidate stale plans",
                control -> {
                    var first = control.propose(safe());
                    var second = control.propose(safe().withReplicas(4));
                    control.apply(first.id(), first.digest(), "first-plan-key");
                    expect(
                            "STALE_PLAN",
                            () -> control.apply(second.id(), second.digest(), "second-plan-key"));
                });
        probe(
                cases,
                "unknown-observation",
                "Missing observation never means convergence",
                control -> {
                    var plan = control.propose(safe());
                    control.apply(plan.id(), plan.digest(), "observed-key");
                    var state = control.injectFault(plan.environmentId(), "observation-lost");
                    require(
                            "UNKNOWN".equals(state.convergence()),
                            "Missing observation was reported as converged");
                    expect(
                            "OBSERVATION_REQUIRED",
                            () -> control.proposeReconciliation(plan.environmentId()));
                });
        probe(
                cases,
                "drift-recovery",
                "Remediation restores approved desired state",
                control -> {
                    var plan = control.propose(safe());
                    control.apply(plan.id(), plan.digest(), "provision-key");
                    control.injectFault(plan.environmentId(), "replica-loss");
                    control.injectFault(plan.environmentId(), "egress-open");
                    var repair = control.proposeReconciliation(plan.environmentId());
                    require(
                            repair.actions().size() == 2,
                            "Expected two field-level corrective actions");
                    var result = control.apply(repair.id(), repair.digest(), "reconcile-key");
                    require(
                            "CONVERGED".equals(result.receipt().environment().convergence()),
                            "Remediation failed to converge");
                    require(
                            result.receipt().environment().desiredVersion() == 1,
                            "Remediation rewrote desired state");
                });
    }

    private void probe(
            List<EvaluationCase> cases,
            String id,
            String name,
            java.util.function.Consumer<ControlPlane> test) {
        long start = System.nanoTime();
        boolean passed = true;
        String detail = "Passed against an isolated sandbox; workbench state unchanged.";
        var registry = new SimpleMeterRegistry();
        try {
            test.accept(
                    new ControlPlane(
                            compiler, new SandboxAdapter(), new AuditLog(clock), clock, registry));
        } catch (RuntimeException | AssertionError failure) {
            passed = false;
            detail = "Safety invariant failed: " + failure.getMessage();
        } finally {
            registry.close();
        }
        cases.add(
                new EvaluationCase(
                        id,
                        name,
                        "execution-safety",
                        "PASS",
                        passed ? "PASS" : "FAIL",
                        passed,
                        (System.nanoTime() - start) / 1_000_000.0,
                        detail));
    }

    static void expect(String code, Runnable operation) {
        try {
            operation.run();
        } catch (PlatformException failure) {
            require(code.equals(failure.code()), "Expected " + code + ", got " + failure.code());
            return;
        }
        throw new AssertionError("Expected rejection " + code);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static IntentSpec safe() {
        return new IntentSpec(
                "probe-api",
                "java21",
                "production",
                "germany",
                true,
                true,
                "pii",
                true,
                3,
                3,
                true,
                true);
    }

    private static double percentile(double[] values, double percentile) {
        return values[Math.max(0, (int) Math.ceil(percentile * values.length) - 1)];
    }

    public List<EvaluationReport> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }
}
