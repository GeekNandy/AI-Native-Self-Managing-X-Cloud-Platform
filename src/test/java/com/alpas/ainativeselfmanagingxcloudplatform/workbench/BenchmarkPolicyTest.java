package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class BenchmarkPolicyTest {
    final tools.jackson.databind.json.JsonMapper json = new WorkbenchConfiguration().jsonMapper();

    RepairBenchmarks.Trial trial(
            String task, String variant, String outcome, int attempts, long tokens) {
        return new RepairBenchmarks.Trial(
                task,
                "test-category",
                1,
                variant,
                "gemini",
                "test-model",
                "reported-test-model",
                "test-run",
                outcome,
                attempts,
                10,
                tokens,
                0,
                tokens,
                null,
                "a".repeat(64),
                true);
    }

    @Test
    void passingAggregateScoreCannotHideAnIndividualRegression() {
        var trials =
                List.of(
                        trial("a", "baseline", "PASS", 2, 100),
                        trial("a", "candidate", "FAILED", 3, 100),
                        trial("b", "baseline", "FAILED", 3, 100),
                        trial("b", "candidate", "PASS", 1, 100));
        assertFalse(RepairBenchmarks.noRegression(trials));
        assertTrue(
                RepairBenchmarks.noRegression(
                        List.of(
                                trial("a", "baseline", "PASS", 2, 100),
                                trial("a", "candidate", "PASS", 1, 90))));
        assertTrue(
                RepairBenchmarks.improves(
                        List.of(
                                trial("a", "baseline", "PASS", 2, 100),
                                trial("a", "candidate", "PASS", 1, 90))));
        assertFalse(
                RepairBenchmarks.improves(
                        List.of(
                                trial("a", "baseline", "PASS", 2, 100),
                                trial("a", "candidate", "PASS", 1, 1000))));
    }

    @Test
    void corpusHasDisjointSplitsAndEvaluationFeedbackIsExcluded() throws Exception {
        var corpus = new RepairCorpus(json);
        assertEquals(25, corpus.dataset().tasks().size());
        assertEquals(20, corpus.evaluation().size());
        var state = StateStore.volatileStore();
        var workspace = new RepositoryWorkspace(state, new CommandRunner(), Clock.systemUTC());
        var task = corpus.evaluation().getFirst();
        var repo =
                workspace.create(
                        "Held-out input", task.files(), task.testClass(), "imported-snapshot");
        var run =
                new CodeModel.RepairRun(
                        "run",
                        repo.id(),
                        repo.head(),
                        task.instruction(),
                        "mutation-search",
                        "v1",
                        "APPROVED",
                        3,
                        30,
                        1,
                        Instant.now(),
                        Instant.now(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        "verified patch",
                        "digest",
                        "ARITHMETIC",
                        repo.head(),
                        "test-only fixture");
        var policies = new PromptPolicies(state, Clock.systemUTC(), corpus, json);
        assertEquals(
                "DEVELOPMENT_FEEDBACK_REQUIRED",
                assertThrows(
                                PlatformException.class,
                                () -> policies.derive(List.of(run), workspace))
                        .code());
        assertEquals(
                "ROLLBACK_TARGET",
                assertThrows(
                                PlatformException.class,
                                () -> policies.rollback("missing", "agent-v1"))
                        .code());
    }
}
