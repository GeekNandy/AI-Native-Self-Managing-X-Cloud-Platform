package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

class RepositoryWorkflowTest {
    @TempDir Path directory;

    @Test
    void actualWorktreeProducesReviewableDiffAndComparesHeadBeforeCommit() {
        try (var store =
                new StateStore(new WorkbenchConfiguration().jsonMapper(), directory.toString())) {
            var repositories =
                    new RepositoryWorkspace(store, new CommandRunner(), Clock.systemUTC());
            var task = new RepairTasks().examples().getFirst();
            var r = repositories.create(task.name(), task.files(), task.testClass(), task.id());
            Path first = repositories.worktree(r.id(), "test-first", r.head()),
                    second = repositories.worktree(r.id(), "test-second", r.head());
            try {
                var candidate =
                        RepairSearch.candidates(
                                        task.files(),
                                        List.of("ARITHMETIC", "BOUNDARY", "EQUALITY"),
                                        5)
                                .getFirst();
                var files = RepairSearch.validateAndApply(task.files(), candidate.changes());
                RepositoryWorkspace.writeFiles(first, files);
                assertEquals(r.head(), repositories.head(r.id()));
                assertTrue(repositories.diff(first).contains("amount - 5"));
                assertEquals(
                        task.files().get("src/test/java/RulesTest.java"),
                        repositories.sources(first).get("src/test/java/RulesTest.java"));
                String commit =
                        repositories.commit(r.id(), first, r.head(), "test-run", "d".repeat(64));
                assertNotEquals(r.head(), commit);
                assertTrue(
                        repositories.matchesApprovedCommit(
                                r.id(), r.head(), "test-run", "d".repeat(64)));
                RepositoryWorkspace.writeFiles(second, files);
                assertEquals(
                        "STALE_PATCH",
                        assertThrows(
                                        PlatformException.class,
                                        () ->
                                                repositories.commit(
                                                        r.id(),
                                                        second,
                                                        r.head(),
                                                        "stale",
                                                        "e".repeat(64)))
                                .code());
            } finally {
                repositories.discard(r.id(), first);
                repositories.discard(r.id(), second);
            }
        }
    }

    @Test
    void rejectsPathTraversalTestEditsAddedFilesAndStaleBeforeHashes() {
        var t = new RepairTasks().examples().getFirst();
        for (String path :
                List.of(
                        "../escape",
                        ".git/config",
                        "src/test/java/RulesTest.java",
                        "src/main/java/New.java"))
            assertEquals(
                    "PATCH_SCOPE_DENIED",
                    assertThrows(
                                    PlatformException.class,
                                    () ->
                                            RepairSearch.validateAndApply(
                                                    t.files(),
                                                    List.of(
                                                            new Change(
                                                                    path,
                                                                    "0".repeat(64),
                                                                    "changed"))))
                            .code());
        var bad = new TreeMap<>(t.files());
        bad.put("src/main/java/../../Leak.java", "public class Leak {}");
        assertThrows(
                PlatformException.class,
                () -> RepositoryWorkspace.validateFiles(bad, t.testClass()));
    }

    @Test
    void mutationSearchSkipsStringsAndCommentsAndCanDeriveDifferentCandidateOrdering() {
        var files =
                Map.of(
                        "src/main/java/Rules.java",
                        "public class Rules { // < >= + -\n"
                                + "String text=\"< +\"; boolean check(int n) { return n > 5; } int"
                                + " calc(int n) { return n + 2; } }");
        var candidates = RepairSearch.candidates(files, RepairSearch.DEFAULT_ORDER, 10);
        assertEquals(2, candidates.size());
        assertEquals("BOUNDARY", candidates.getFirst().kind());
        assertTrue(candidates.getFirst().changes().getFirst().content().contains("// < >= + -"));
        assertTrue(candidates.getFirst().changes().getFirst().content().contains("\"< +\""));
        assertEquals(
                "ARITHMETIC",
                RepairSearch.candidates(files, List.of("ARITHMETIC", "BOUNDARY", "EQUALITY"), 10)
                        .getFirst()
                        .kind());
    }

    @Test
    void commandRunnerBoundsOutputTimeAndDoesNotPassCredentials() {
        var runner = new CommandRunner();
        var deadline = runner.run(null, Duration.ofMillis(50), List.of("sleep", "5"));
        assertTrue(deadline.timedOut());
        assertTrue(deadline.durationMs() < 2000);
        var environment = runner.run(null, Duration.ofSeconds(2), List.of("env"));
        assertFalse(environment.output().contains("HTTPS_PROXY="));
        assertFalse(environment.output().contains("PLATFORM_OPERATOR_TOKEN="));
    }

    @Test
    void sourceArchiveUsesValidTarHeadersAndFixedArguments() {
        var task = new RepairTasks().examples().getFirst();
        var result =
                new CommandRunner()
                        .run(
                                null,
                                Duration.ofSeconds(3),
                                List.of("tar", "--no-same-owner", "-tf", "-"),
                                DockerSandbox.tar(task.files()));
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("src/test/java/RulesTest.java"));
    }

    @Test
    void interruptingAProcessDoesNotWaitForItsBlockedOutputReader() throws Exception {
        var result = new java.util.concurrent.CompletableFuture<String>();
        var thread =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        new CommandRunner()
                                                .run(
                                                        null,
                                                        Duration.ofSeconds(60),
                                                        List.of("sleep", "60"));
                                        result.complete("unexpected-success");
                                    } catch (PlatformException expected) {
                                        result.complete(expected.code());
                                    }
                                });
        Thread.sleep(100);
        thread.interrupt();
        assertEquals("RUN_CANCELLED", result.get(2, java.util.concurrent.TimeUnit.SECONDS));
        thread.join(2000);
        assertFalse(thread.isAlive());
    }
}
