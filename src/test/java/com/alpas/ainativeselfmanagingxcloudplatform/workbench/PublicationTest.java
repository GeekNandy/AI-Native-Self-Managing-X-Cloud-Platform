package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

class PublicationTest {
    final tools.jackson.databind.json.JsonMapper json = new WorkbenchConfiguration().jsonMapper();

    @Test
    void lostPullRequestResponseIsReconciledWithoutCreatingAnotherPrAndStaleBaseIsRejected()
            throws Exception {
        var created = new AtomicInteger();
        var branch = new AtomicBoolean();
        var remoteBase = new AtomicReference<>("a".repeat(40));
        var message = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath(),
                            method = exchange.getRequestMethod();
                    Object result = Map.of();
                    int status = 200;
                    var body =
                            method.equals("POST")
                                    ? json.readTree(exchange.getRequestBody().readAllBytes())
                                    : null;
                    if (path.endsWith("/commits/feature"))
                        result =
                                Map.of(
                                        "sha",
                                        remoteBase.get(),
                                        "commit",
                                        Map.of("tree", Map.of("sha", "b".repeat(40))));
                    else if (path.endsWith("/git/trees")) {
                        assertEquals(
                                "project/src/main/java/Rules.java",
                                body.path("tree").path(0).path("path").asString());
                        result = Map.of("sha", "c".repeat(40));
                    } else if (path.endsWith("/git/commits")) {
                        message.set(body.path("message").asString());
                        result = Map.of("sha", "d".repeat(40));
                    } else if (path.endsWith("/git/refs")) {
                        branch.set(true);
                        result = Map.of("ref", body.path("ref").asString());
                    } else if (path.contains("/git/ref/heads/")) {
                        if (!branch.get()) status = 404;
                        else result = Map.of("object", Map.of("sha", "d".repeat(40)));
                    } else if (path.endsWith("/git/commits/" + "d".repeat(40)))
                        result =
                                Map.of(
                                        "tree",
                                        Map.of("sha", "c".repeat(40)),
                                        "parents",
                                        List.of(Map.of("sha", "a".repeat(40))),
                                        "message",
                                        message.get());
                    else if (path.endsWith("/pulls") && method.equals("POST")) {
                        assertTrue(body.path("draft").asBoolean());
                        created.incrementAndGet();
                        status = 503;
                        result = Map.of("message", "Response lost after remote write");
                    } else if (path.endsWith("/pulls")) {
                        String query = exchange.getRequestURI().getQuery();
                        String head =
                                query.substring(
                                        query.indexOf("head=") + 5, query.indexOf("&base="));
                        head = URLDecoder.decode(head, java.nio.charset.StandardCharsets.UTF_8);
                        head = head.substring(head.indexOf(':') + 1);
                        result =
                                created.get() == 0
                                        ? List.of()
                                        : List.of(
                                                Map.of(
                                                        "number",
                                                        17,
                                                        "head",
                                                        Map.of("ref", head),
                                                        "base",
                                                        Map.of("ref", "feature")));
                    } else status = 404;
                    byte[] bytes = json.writeValueAsBytes(result);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        var clock = Clock.systemUTC();
        var state = StateStore.volatileStore();
        var workspace = new RepositoryWorkspace(state, new CommandRunner(), clock);
        var task = new RepairTasks().examples().getFirst();
        var repo =
                workspace.create(
                        task.name(),
                        task.files(),
                        task.testClass(),
                        "github:owner/repo@" + "a".repeat(40));
        var fakeSandbox =
                new SandboxExecutor() {
                    public Map<String, Object> capabilities() {
                        return Map.of("available", true);
                    }

                    public TestResult test(
                            Map<String, String> files, String test, Duration budget) {
                        boolean pass = files.get("src/main/java/Rules.java").contains("amount - 5");
                        return new TestResult(
                                pass ? "PASS" : "FAIL",
                                pass ? 0 : 1,
                                false,
                                false,
                                1,
                                "test-only executor contract");
                    }
                };
        var repairs =
                new RepairService(
                        workspace,
                        fakeSandbox,
                        new GeminiPatchPlanner("", "", json),
                        new RepairTasks(),
                        state,
                        new AuditLog(clock, state),
                        clock,
                        new IntentCompiler(new PolicyEngine(), json));
        try {
            var run =
                    AgentLoopTest.await(
                            repairs,
                            repairs.submit(repo.id(), task.instruction(), "mutation-search", 8, 30)
                                    .id());
            run = repairs.approve(run.id(), run.digest());
            state.put(
                    "github-provenance",
                    repo.id(),
                    new GitHubRepositories.Provenance(
                            repo.id(),
                            "owner/repo",
                            "feature",
                            "a".repeat(40),
                            "project",
                            "java21-main-v1",
                            RepairCorpus.testDigest(task.files()),
                            clock.instant()));
            var api =
                    new GitHubApi(
                            "test-only-private-token",
                            json,
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var imports = new GitHubRepositories(workspace, api, state, clock);
            var publication =
                    new GitHubPublications(
                            api,
                            imports,
                            repairs,
                            workspace,
                            state,
                            clock,
                            "owner/repo",
                            "feature");
            String id = run.id();
            var preview = publication.preview(id);
            assertEquals(
                    "DIGEST_MISMATCH",
                    assertThrows(
                                    PlatformException.class,
                                    () -> publication.publish(id, "0".repeat(64)))
                            .code());
            remoteBase.set("e".repeat(40));
            assertEquals(
                    "STALE_GITHUB_BASE",
                    assertThrows(
                                    PlatformException.class,
                                    () -> publication.publish(id, preview.approvalDigest()))
                            .code());
            assertFalse(branch.get());
            remoteBase.set("a".repeat(40));
            assertThrows(
                    PlatformException.class,
                    () -> publication.publish(id, preview.approvalDigest()));
            assertEquals(1, created.get());
            var receipt = publication.publish(id, preview.approvalDigest());
            assertEquals("PR_CREATED", receipt.status());
            assertEquals("https://github.com/owner/repo/pull/17", receipt.pullRequestUrl());
            assertEquals(receipt, publication.publish(id, preview.approvalDigest()));
            assertEquals(1, created.get());
        } finally {
            repairs.close();
            server.stop(0);
        }
    }
}
