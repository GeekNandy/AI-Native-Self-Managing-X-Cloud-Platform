package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

/**
 * Orchestration contract with a fake executor; actual corpus execution has a separate Docker test.
 */
class BenchmarkOrchestrationTest {
    @Test
    void bothVariantsRunAllEvaluationInputsAndAnUnimprovedPolicyCannotBePromoted()
            throws Exception {
        var json = new WorkbenchConfiguration().jsonMapper();
        var store = StateStore.volatileStore();
        var clock = Clock.systemUTC();
        var corpus = new RepairCorpus(json);
        var policies = new PromptPolicies(store, clock, corpus, json);
        var repositories = new RepositoryWorkspace(store, new CommandRunner(), clock);
        var models = new GeminiPatchPlanner("", "", json);
        var executor =
                new SandboxExecutor() {
                    public Map<String, Object> capabilities() {
                        return Map.of("available", true);
                    }

                    public TestResult test(
                            Map<String, String> files, String test, Duration budget) {
                        boolean probe = test.equals("ProbeTest");
                        return new TestResult(
                                probe ? "PASS" : "FAIL",
                                probe ? 0 : 1,
                                false,
                                false,
                                1,
                                "contract-executor fixture");
                    }
                };
        try (var repairs =
                new RepairService(
                        repositories,
                        executor,
                        models,
                        new RepairTasks(),
                        store,
                        new AuditLog(clock, store),
                        clock,
                        new IntentCompiler(new PolicyEngine(), json))) {
            var benchmarks =
                    new RepairBenchmarks(
                            corpus,
                            repairs,
                            repositories,
                            policies,
                            models,
                            executor,
                            store,
                            clock);
            try {
                var r =
                        benchmarks.start(
                                new RepairBenchmarks.Request(
                                        "mutation-search",
                                        "mutation-search",
                                        "agent-v1",
                                        "agent-v1",
                                        1,
                                        1,
                                        20));
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (Set.of("QUEUED", "RUNNING").contains(r.status())
                        && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                    r = benchmarks.require(r.id());
                }
                assertEquals("COMPLETED", r.status(), r.detail());
                assertEquals(40, r.trials().size());
                assertEquals(
                        20,
                        r.trials().stream().map(RepairBenchmarks.Trial::taskId).distinct().count());
                assertTrue(r.safety().get("paired-completeness"));
                assertFalse(r.safety().get("candidate-quality-floor"));
                assertFalse(r.promotable());
                String id = r.id();
                assertEquals(
                        "PROMOTION_GATE_FAILED",
                        assertThrows(PlatformException.class, () -> benchmarks.promote(id)).code());
                assertTrue(r.trials().stream().allMatch(t -> t.costUsd() == null));
                assertEquals("agent-v1", policies.active().version());
            } finally {
                benchmarks.close();
            }
        }
    }
}
