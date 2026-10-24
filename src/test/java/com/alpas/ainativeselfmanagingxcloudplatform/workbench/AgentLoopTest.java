package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class AgentLoopTest {
    @Test
    void modelRevisesTheFailedCandidateAndFinalChangesRemainBoundToOriginalSource()
            throws Exception {
        var json = new WorkbenchConfiguration().jsonMapper();
        var state = StateStore.volatileStore();
        var clock = Clock.systemUTC();
        var repositories = new RepositoryWorkspace(state, new CommandRunner(), clock);
        var files =
                Map.of(
                        "src/main/java/Rules.java",
                        "class Rules { static int add(int a,int b){return a-b;} static int"
                                + " twice(int n){return n+1;} }",
                        "src/test/java/RulesTest.java",
                        "class RulesTest { public static void main(String[] a) {} }");
        var repository =
                repositories.create(
                        "Two-stage regression", files, "RulesTest", "test-only-contract-fixture");
        var sandbox =
                new SandboxExecutor() {
                    public Map<String, Object> capabilities() {
                        return Map.of("available", true);
                    }

                    public TestResult test(
                            Map<String, String> input, String test, Duration budget) {
                        boolean pass =
                                input.get("src/main/java/Rules.java").contains("a+b")
                                        && input.get("src/main/java/Rules.java").contains("n*2");
                        return new TestResult(
                                pass ? "PASS" : "FAIL",
                                pass ? 0 : 1,
                                false,
                                false,
                                1,
                                pass ? "PASS" : "two regressions remain");
                    }
                };
        var planner =
                new GeminiPatchPlanner("", "", json) {
                    int calls;

                    @Override
                    public boolean configured(String provider) {
                        return true;
                    }

                    @Override
                    public List<Change> propose(
                            String provider,
                            String instruction,
                            Map<String, String> input,
                            TestResult tests,
                            List<Step> steps,
                            Duration budget) {
                        String source = input.get("src/main/java/Rules.java");
                        if (++calls == 2)
                            assertTrue(
                                    source.contains("a+b"),
                                    "Second turn must see the previous candidate");
                        String fixed =
                                calls == 1
                                        ? source.replace("a-b", "a+b")
                                        : source.replace("n+1", "n*2");
                        return List.of(
                                new Change(
                                        "src/main/java/Rules.java",
                                        IntentCompiler.digest(source),
                                        fixed));
                    }
                };
        try (var service =
                new RepairService(
                        repositories,
                        sandbox,
                        planner,
                        new RepairTasks(),
                        state,
                        new AuditLog(clock, state),
                        clock,
                        new IntentCompiler(new PolicyEngine(), json))) {
            var queued =
                    service.submit(
                            repository.id(),
                            "Fix both regressions without changing tests",
                            "gemini",
                            3,
                            30);
            var run = await(service, queued.id());
            assertEquals("AWAITING_REVIEW", run.status(), run.detail());
            assertEquals(2, run.attempts());
            assertEquals(
                    IntentCompiler.digest(files.get("src/main/java/Rules.java")),
                    run.changes().getFirst().beforeDigest());
            assertTrue(run.patch().contains("a+b"));
            assertTrue(run.patch().contains("n*2"));
            assertTrue(run.steps().stream().anyMatch(s -> s.tool().equals("repository.search")));
            var approved = service.approve(run.id(), run.digest());
            assertEquals("APPROVED", approved.status());
            assertEquals(
                    files.get("src/test/java/RulesTest.java"),
                    repositories.files(repository.id()).get("src/test/java/RulesTest.java"));
        }
    }

    static RepairRun await(RepairService service, String id) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        RepairRun run;
        do {
            Thread.sleep(10);
            run = service.require(id);
        } while (Set.of("QUEUED", "RUNNING").contains(run.status()) && System.nanoTime() < end);
        return run;
    }

    @Test
    void retrievalRespectsFullFileAndByteBudgets() {
        var files = new TreeMap<String, String>();
        files.put("src/main/java/Rules.java", "class Rules {} ");
        files.put("src/test/java/RulesTest.java", "class RulesTest {}");
        files.put("src/main/java/Large.java", "x".repeat(25000));
        var selection = SourceContext.select(files, "Repair Rules", "RulesTest failed");
        assertTrue(selection.bytes() <= 24000);
        assertFalse(selection.files().containsKey("src/main/java/Large.java"));
        assertEquals(
                files.get("src/main/java/Rules.java"),
                selection.files().get("src/main/java/Rules.java"));
    }
}
