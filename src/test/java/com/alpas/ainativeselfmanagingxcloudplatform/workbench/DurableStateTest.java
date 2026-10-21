package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

class DurableStateTest {
    @TempDir Path directory;
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @org.junit.jupiter.api.AfterEach
    void closeMetrics() {
        metrics.close();
    }

    private static final Clock CLOCK = Clock.systemUTC();
    private final tools.jackson.databind.json.JsonMapper json =
            new WorkbenchConfiguration().jsonMapper();
    private final IntentCompiler compiler = new IntentCompiler(new PolicyEngine(), json);

    private IntentSpec safe() {
        return EvaluationService.safe();
    }

    @Test
    void restartRestoresReceiptDesiredStatePlansFeedbackAndAudit() throws Exception {
        String planId, digest, runId;
        Receipt receipt;
        try (var store = new StateStore(json, directory.toString())) {
            var audit = new AuditLog(CLOCK, store);
            var control =
                    new ControlPlane(
                            compiler, new SandboxAdapter(store), audit, CLOCK, metrics, store);
            var catalog = new ScenarioCatalog(json);
            var interpreter = new ConfiguredInterpreter("fixture", "", "", catalog, json);
            var agents = new AgentService(interpreter, control, CLOCK, audit, metrics, store);
            var feedback = new FeedbackStore(agents, CLOCK, audit, store);
            var run = agents.run(catalog.examples().getFirst().prompt());
            var plan = run.plan();
            runId = run.id();
            planId = plan.id();
            digest = plan.digest();
            receipt = control.apply(planId, digest, "durable-retry-key").receipt();
            feedback.capture(
                    runId, "Retain a reviewed regression after restart.", plan.intent(), "ALLOW");
            control.injectFault(plan.environmentId(), "replica-loss");
        }
        try (var store = new StateStore(json, directory.toString())) {
            var audit = new AuditLog(CLOCK, store);
            var control =
                    new ControlPlane(
                            compiler, new SandboxAdapter(store), audit, CLOCK, metrics, store);
            var catalog = new ScenarioCatalog(json);
            var agents =
                    new AgentService(
                            new ConfiguredInterpreter("fixture", "", "", catalog, json),
                            control,
                            CLOCK,
                            audit,
                            metrics,
                            store);
            var feedback = new FeedbackStore(agents, CLOCK, audit, store);
            assertEquals(receipt, control.apply(planId, digest, "durable-retry-key").receipt());
            assertTrue(control.apply(planId, digest, "durable-retry-key").replayed());
            var state = control.environment(receipt.environment().id());
            assertEquals(1, state.desiredVersion());
            assertEquals("DRIFT_DETECTED", state.convergence());
            assertEquals(1, feedback.list().size());
            assertEquals(runId, agents.requireRun(runId).id());
            assertTrue(
                    audit.list(null).stream()
                            .anyMatch(e -> e.type().equals("OPERATION_SUCCEEDED")));
            long last = audit.list(null).getLast().sequence();
            audit.append("TEST", "test", null, "restart", "sequence");
            assertEquals(last + 1, audit.list(null).getLast().sequence());
        }
    }

    @Test
    void interruptedExternalWriteInvalidatesApprovalAndRequiresFreshObservation() {
        String id, digest, environment;
        try (var store = new StateStore(json, directory.toString())) {
            var adapter = new SandboxAdapter(store);
            var control =
                    new ControlPlane(
                            compiler, adapter, new AuditLog(CLOCK, store), CLOCK, metrics, store);
            var plan = control.propose(safe());
            id = plan.id();
            digest = plan.digest();
            environment = plan.environmentId();
            // Simulate process death after an external side effect, before its receipt transaction.
            adapter.apply(environment, plan.intent());
            store.put(
                    "environments",
                    environment,
                    new ControlPlane.EnvironmentSnapshot(
                            environment, 0, 0, null, null, List.of(), id));
        }
        try (var store = new StateStore(json, directory.toString())) {
            var audit = new AuditLog(CLOCK, store);
            var control =
                    new ControlPlane(
                            compiler, new SandboxAdapter(store), audit, CLOCK, metrics, store);
            assertEquals("UNKNOWN", control.environment(environment).convergence());
            assertNull(control.environment(environment).desired());
            assertTrue(
                    control.environments().stream()
                            .anyMatch(
                                    e ->
                                            e.id().equals(environment)
                                                    && e.convergence().equals("UNKNOWN")));
            var error =
                    assertThrows(
                            PlatformException.class,
                            () -> control.apply(id, digest, "interrupted-key"));
            assertEquals("STALE_PLAN", error.code());
            assertNotNull(control.refresh(environment).observed());
            var replacement = control.propose(safe());
            assertEquals(
                    2,
                    replacement.baseRevision()); // recovery + explicit observation advance revision
            // twice
            assertEquals(
                    "CONVERGED",
                    control.apply(replacement.id(), replacement.digest(), "fresh-review-key")
                            .receipt()
                            .environment()
                            .convergence());
            assertTrue(
                    audit.list(null).stream()
                            .anyMatch(e -> e.type().equals("OPERATION_RECOVERY_REQUIRED")));
        }
    }

    @Test
    void embeddedStateCreatesAndReopensItsDurableFile() {
        try (var store = new StateStore(json, directory.toString())) {
            store.put("test", "one", new AuditLog.Snapshot(List.of()));
            assertTrue(java.nio.file.Files.exists(directory.resolve("state.mv.db")));
        }
    }

    @Test
    void failedStorageCannotReplayAnUncommittedInMemoryMutation() {
        var store = new StateStore(json, directory.toString());
        var adapter = new SandboxAdapter(store);
        var control =
                new ControlPlane(
                        compiler, adapter, new AuditLog(CLOCK, store), CLOCK, metrics, store);
        var plan = control.propose(safe());
        store.close();
        assertEquals(
                "STATE_UNAVAILABLE",
                assertThrows(
                                PlatformException.class,
                                () -> control.apply(plan.id(), plan.digest(), "closed-store-key"))
                        .code());
        assertNull(adapter.observe(plan.environmentId()));
    }
}
