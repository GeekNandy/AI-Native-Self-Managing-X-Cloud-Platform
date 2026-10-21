package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.tools.ToolProvider;

class ControlPlaneTest {
    final JsonMapper json = new WorkbenchConfiguration().jsonMapper();
    final IntentCompiler compiler = new IntentCompiler(new PolicyEngine(), json);
    final MutableClock clock = new MutableClock();
    AuditLog audit;
    ControlPlane control;

    @BeforeEach
    void setup() {
        audit = new AuditLog(clock);
        control = rig(new SandboxAdapter(), compiler);
    }

    ControlPlane rig(SandboxAdapter adapter, IntentCompiler compiler) {
        return new ControlPlane(compiler, adapter, audit, clock, new SimpleMeterRegistry());
    }

    IntentSpec safe() {
        return EvaluationService.safe();
    }

    void rejected(String code, Runnable action) {
        var failure = assertThrows(PlatformException.class, action::run);
        assertEquals(code, failure.code());
    }

    @Test
    void rejectsWrongDigestAndDeniedPlanWithoutCommittingDesiredState() {
        var plan = control.propose(safe());
        rejected(
                "DIGEST_MISMATCH",
                () -> control.apply(plan.id(), "a".repeat(64), "digest-test-key"));
        var denied = control.propose(safe().withRestrictedEgress(false));
        rejected(
                "POLICY_DENIED",
                () -> control.apply(denied.id(), denied.digest(), "denied-test-key"));
        assertTrue(control.environments().isEmpty());
        assertEquals(
                2,
                audit.list(null).stream()
                        .filter(event -> event.type().equals("EXECUTION_REJECTED"))
                        .count());
    }

    @Test
    void planExpiresAtTheDeadlineWithoutChangingState() {
        var plan = control.propose(safe());
        clock.now = clock.now.plus(Duration.ofMinutes(15));
        rejected("PLAN_EXPIRED", () -> control.apply(plan.id(), plan.digest(), "expired-test-key"));
        assertTrue(control.environments().isEmpty());
    }

    @Test
    void approvalIsRecheckedAgainstCurrentPolicy() {
        var dynamicPolicy =
                new PolicyEngine() {
                    boolean deny;

                    @Override
                    public PolicyReport evaluate(IntentSpec spec) {
                        var result = super.evaluate(spec);
                        if (deny)
                            return new PolicyReport("changed-policy", "DENY", result.checks());
                        deny = true;
                        return result;
                    }
                };
        var local = rig(new SandboxAdapter(), new IntentCompiler(dynamicPolicy, json));
        var plan = local.propose(safe());
        rejected("POLICY_CHANGED", () -> local.apply(plan.id(), plan.digest(), "new-policy-key"));
        assertTrue(local.environments().isEmpty());
    }

    @Test
    void sameKeyCannotBeReboundToAnotherPlan() {
        var first = control.propose(safe());
        control.apply(first.id(), first.digest(), "shared-operation-key");
        var second = control.propose(safe().withReplicas(4));
        rejected(
                "IDEMPOTENCY_CONFLICT",
                () -> control.apply(second.id(), second.digest(), "shared-operation-key"));
        assertEquals(3, control.environment(first.environmentId()).desired().replicas());
    }

    @Test
    void completedReceiptSurvivesExpiryAndEvictionOfPlanDetails() {
        var plan = control.propose(safe());
        var receipt = control.apply(plan.id(), plan.digest(), "retained-receipt-key");
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        control.propose(safe().withReplicas(4));
        rejected("PLAN_NOT_FOUND", () -> control.plan(plan.id()));
        var retry = control.apply(plan.id(), plan.digest(), "retained-receipt-key");
        assertTrue(retry.replayed());
        assertEquals(receipt.receipt(), retry.receipt());
        rejected(
                "ALREADY_APPLIED",
                () -> control.apply(plan.id(), plan.digest(), "replacement-receipt-key"));
        assertEquals(1, control.environment(plan.environmentId()).desiredVersion());
    }

    @Test
    void concurrentRetriesCommitOneOperationAndReturnTheSameReceipt() throws Exception {
        var plan = control.propose(safe());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Callable<ApplyResult>>();
            for (int i = 0; i < 32; i++)
                jobs.add(() -> control.apply(plan.id(), plan.digest(), "concurrent-retry-key"));
            var results =
                    pool.invokeAll(jobs).stream()
                            .map(
                                    future -> {
                                        try {
                                            return future.get();
                                        } catch (Exception failure) {
                                            throw new RuntimeException(failure);
                                        }
                                    })
                            .toList();
            assertEquals(1, results.stream().filter(result -> !result.replayed()).count());
            assertEquals(
                    1,
                    results.stream()
                            .map(result -> result.receipt().operationId())
                            .distinct()
                            .count());
        }
        assertEquals(1, control.environment(plan.environmentId()).desiredVersion());
        assertEquals(
                1,
                audit.list(null).stream()
                        .filter(event -> event.type().equals("OPERATION_SUCCEEDED"))
                        .count());
    }

    @Test
    void competingPlansCannotBothCommitAgainstTheSameRevision() throws Exception {
        var first = control.propose(safe());
        var second = control.propose(safe().withReplicas(4));
        var committed = new AtomicInteger();
        var rejected = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs =
                    List.<Callable<Void>>of(
                            () -> {
                                compete(first, "competing-first", committed, rejected);
                                return null;
                            },
                            () -> {
                                compete(second, "competing-second", committed, rejected);
                                return null;
                            });
            for (var future : pool.invokeAll(jobs)) future.get();
        }
        assertEquals(1, committed.get());
        assertEquals(1, rejected.get());
        assertEquals(1, control.environment(first.environmentId()).desiredVersion());
    }

    void compete(Plan plan, String key, AtomicInteger committed, AtomicInteger rejected) {
        try {
            control.apply(plan.id(), plan.digest(), key);
            committed.incrementAndGet();
        } catch (PlatformException failure) {
            assertEquals("STALE_PLAN", failure.code());
            rejected.incrementAndGet();
        }
    }

    @Test
    void lostObservationBlocksRecoveryAndRestoringObservationRevealsDrift() {
        var plan = control.propose(safe());
        control.apply(plan.id(), plan.digest(), "initial-observed-key");
        control.injectFault(plan.environmentId(), "replica-loss");
        assertEquals(
                "UNKNOWN",
                control.injectFault(plan.environmentId(), "observation-lost").convergence());
        rejected("OBSERVATION_REQUIRED", () -> control.proposeReconciliation(plan.environmentId()));
        var restored = control.injectFault(plan.environmentId(), "observation-restored");
        assertEquals("DRIFT_DETECTED", restored.convergence());
        var repair = control.proposeReconciliation(plan.environmentId());
        var result = control.apply(repair.id(), repair.digest(), "restore-observed-key");
        assertEquals("CONVERGED", result.receipt().environment().convergence());
        assertEquals(1, result.receipt().environment().desiredVersion());
    }

    @Test
    void newObservationInvalidatesAReviewedRecoveryPlan() {
        var plan = control.propose(safe());
        control.apply(plan.id(), plan.digest(), "initial-recovery-key");
        control.injectFault(plan.environmentId(), "replica-loss");
        var recovery = control.proposeReconciliation(plan.environmentId());
        control.injectFault(plan.environmentId(), "egress-open");
        rejected(
                "STALE_PLAN",
                () -> control.apply(recovery.id(), recovery.digest(), "stale-recovery-key"));
        assertEquals(2, control.environment(plan.environmentId()).drift().size());
    }

    @Test
    void partialAdapterFailureKeepsCommittedDesiredStateAndCanBeReconciled() {
        var adapter =
                new SandboxAdapter() {
                    @Override
                    public IntentSpec apply(String id, IntentSpec target) {
                        var observed = super.apply(id, target);
                        if (target.replicas() == 4)
                            throw new IllegalStateException(
                                    "Simulated failure after runtime write");
                        return observed;
                    }
                };
        var local = rig(adapter, compiler);
        var first = local.propose(safe());
        local.apply(first.id(), first.digest(), "initial-partial-key");
        var update = local.propose(safe().withReplicas(4));
        rejected(
                "ADAPTER_FAILED",
                () -> local.apply(update.id(), update.digest(), "failed-partial-key"));
        var after = local.environment(first.environmentId());
        assertEquals(3, after.desired().replicas());
        assertEquals(4, after.observed().replicas());
        assertEquals("DRIFT_DETECTED", after.convergence());
        rejected(
                "STALE_PLAN",
                () -> local.apply(update.id(), update.digest(), "failed-partial-key"));
        var recovery = local.proposeReconciliation(first.environmentId());
        assertEquals(
                "CONVERGED",
                local.apply(recovery.id(), recovery.digest(), "partial-recovery-key")
                        .receipt()
                        .environment()
                        .convergence());
    }

    @Test
    void compilerRejectsNamesThatCouldInjectGeneratedFiles() {
        var bad =
                new IntentSpec(
                        "api\nkind: Secret",
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
        rejected("INVALID_INTENT", () -> control.propose(bad));
        assertTrue(control.environments().isEmpty());
    }

    @Test
    void generatedJavaWorkloadActuallyCompiles(@TempDir Path directory) throws Exception {
        var artifact =
                compiler.artifacts(safe()).stream()
                        .filter(file -> file.path().equals("Main.java"))
                        .findFirst()
                        .orElseThrow();
        var source = directory.resolve("Main.java");
        Files.writeString(source, artifact.content());
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "--release", "21", source.toString()));
        assertTrue(Files.exists(directory.resolve("Main.class")));
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-21T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
