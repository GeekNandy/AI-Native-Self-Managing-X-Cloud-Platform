package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

class GitHubImportTest {
    final tools.jackson.databind.json.JsonMapper json = new WorkbenchConfiguration().jsonMapper();

    String blob(String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        var hash = java.security.MessageDigest.getInstance("SHA-1");
        hash.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        hash.update(bytes);
        return HexFormat.of().formatHex(hash.digest());
    }

    @Test
    void pinsCommitVerifiesBlobIntegrityAndRetainsProvenance() throws Exception {
        var task = new RepairTasks().examples().getFirst();
        var responses = new HashMap<String, Object>();
        var tree = new ArrayList<Map<String, Object>>();
        for (var file : task.files().entrySet()) {
            String sha = blob(file.getValue());
            tree.add(
                    Map.of(
                            "path",
                            "project/" + file.getKey(),
                            "mode",
                            "100644",
                            "type",
                            "blob",
                            "size",
                            file.getValue().length(),
                            "sha",
                            sha));
            responses.put(
                    "/repos/owner/repo/git/blobs/" + sha,
                    Map.of(
                            "encoding",
                            "base64",
                            "content",
                            Base64.getEncoder()
                                    .encodeToString(
                                            file.getValue().getBytes(StandardCharsets.UTF_8))));
        }
        responses.put(
                "/repos/owner/repo/commits/feature",
                Map.of(
                        "sha",
                        "a".repeat(40),
                        "commit",
                        Map.of("tree", Map.of("sha", "b".repeat(40)))));
        responses.put(
                "/repos/owner/repo/git/trees/" + "b".repeat(40),
                Map.of("truncated", false, "tree", tree));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    var value = responses.get(exchange.getRequestURI().getPath());
                    byte[] data = json.writeValueAsBytes(value);
                    exchange.sendResponseHeaders(value == null ? 404 : 200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        try {
            var state = StateStore.volatileStore();
            var workspace = new RepositoryWorkspace(state, new CommandRunner(), Clock.systemUTC());
            var importer =
                    new GitHubRepositories(
                            workspace,
                            new GitHubApi(
                                    "",
                                    json,
                                    URI.create(
                                            "http://127.0.0.1:" + server.getAddress().getPort())),
                            state,
                            Clock.systemUTC());
            var imported =
                    importer.ingest(
                            new GitHubRepositories.Import(
                                    "owner/repo", "feature", "project", task.testClass()));
            assertEquals(task.files(), workspace.files(imported.id()));
            assertEquals("a".repeat(40), importer.provenance(imported.id()).pinnedCommit());
            assertNotEquals(imported.head(), importer.provenance(imported.id()).pinnedCommit());
            assertThrows(
                    PlatformException.class,
                    () ->
                            importer.ingest(
                                    new GitHubRepositories.Import(
                                            "https://attacker.test/repo",
                                            "feature",
                                            "project",
                                            task.testClass())));
            responses.replaceAll(
                    (path, value) ->
                            path.contains("/blobs/")
                                    ? Map.of(
                                            "encoding",
                                            "base64",
                                            "content",
                                            Base64.getEncoder()
                                                    .encodeToString(
                                                            "tampered"
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)))
                                    : value);
            assertEquals(
                    "INVALID_GITHUB_SNAPSHOT",
                    assertThrows(
                                    PlatformException.class,
                                    () ->
                                            importer.ingest(
                                                    new GitHubRepositories.Import(
                                                            "owner/repo",
                                                            "feature",
                                                            "project",
                                                            task.testClass())))
                            .code());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void declaredMavenProfileRejectsExternalEntitiesAndProjectBuildHooks() throws Exception {
        var pom =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("examples/ledger-service/pom.xml"));
        assertDoesNotThrow(() -> BuildProfile.validatePom(pom));
        assertThrows(
                PlatformException.class,
                () ->
                        BuildProfile.validatePom(
                                pom.replace("</project>", "<build><plugins/></build></project>")));
        assertThrows(
                PlatformException.class,
                () ->
                        BuildProfile.validatePom(
                                "<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                                        + pom));
        assertThrows(
                PlatformException.class,
                () -> BuildProfile.validatePom(pom.replace("6.0.3", "5.0.0")));
    }
}
