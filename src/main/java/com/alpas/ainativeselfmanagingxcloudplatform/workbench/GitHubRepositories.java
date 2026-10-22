package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public final class GitHubRepositories {
    public record Import(String repository, String ref, String subdirectory, String testClass) {}

    public record Provenance(
            String repositoryId,
            String repository,
            String requestedRef,
            String pinnedCommit,
            String subdirectory,
            String profile,
            String immutableInputsDigest,
            Instant importedAt) {}

    private final RepositoryWorkspace workspace;
    private final GitHubApi github;
    private final StateStore store;
    private final Clock clock;
    private final Semaphore slot = new Semaphore(1);

    public GitHubRepositories(
            RepositoryWorkspace workspace, GitHubApi github, StateStore store, Clock clock) {
        this.workspace = workspace;
        this.github = github;
        this.store = store;
        this.clock = clock;
    }

    public Provenance provenance(String id) {
        workspace.require(id);
        return store.all("github-provenance", Provenance.class).stream()
                .filter(p -> p.repositoryId().equals(id))
                .findFirst()
                .orElse(null);
    }

    public Repository ingest(Import request) {
        if (request.repository() == null
                || !request.repository()
                        .matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
                || request.ref() == null
                || !request.ref().matches("[A-Za-z0-9][A-Za-z0-9_./-]{0,99}")
                || request.subdirectory() == null
                || !(request.subdirectory().isEmpty()
                        || request.subdirectory().matches("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*")))
            throw new PlatformException(
                    400,
                    "INVALID_GITHUB_IMPORT",
                    "Provide owner/repository, branch or SHA, and an optional simple"
                            + " subdirectory.");
        if (!slot.tryAcquire())
            throw new PlatformException(
                    429, "IMPORT_BUSY", "A repository import is already running.");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        try {
            String api = "/repos/" + request.repository();
            var commit = github.get(api + "/commits/" + GitHubApi.segment(request.ref()));
            String sha = commit.path("sha").asString(),
                    tree = commit.path("commit").path("tree").path("sha").asString();
            if (!sha.matches("[a-f0-9]{40}") || !tree.matches("[a-f0-9]{40}"))
                throw new IllegalStateException();
            var listing = github.get(api + "/git/trees/" + tree + "?recursive=1");
            if (listing.path("truncated").asBoolean())
                throw new PlatformException(
                        422,
                        "REPOSITORY_TOO_LARGE",
                        "GitHub returned a truncated tree; select a smaller repository.");
            String prefix = request.subdirectory().isEmpty() ? "" : request.subdirectory() + "/";
            var files = new TreeMap<String, String>();
            for (var entry : listing.path("tree")) {
                String path = entry.path("path").asString();
                if (!path.startsWith(prefix)) continue;
                path = path.substring(prefix.length());
                if (entry.path("mode").asString().equals("120000")
                        || entry.path("type").asString().equals("commit"))
                    throw new PlatformException(
                            422,
                            "UNSUPPORTED_REPOSITORY",
                            "Symlinks and submodules are not accepted in the selected project.");
                if (!(path.equals("pom.xml") || path.matches("src/(main|test)/java/.+\\.java")))
                    continue;
                if (files.size() >= 25
                        || entry.path("size").asLong(0) > 32768
                        || System.nanoTime() > deadline)
                    throw new PlatformException(
                            422,
                            "REPOSITORY_TOO_LARGE",
                            "Import is limited to 25 files, 32 KB each and 90 seconds.");
                String blob = entry.path("sha").asString();
                if (!blob.matches("[a-f0-9]{40}")) throw new IllegalStateException();
                var response = github.get(api + "/git/blobs/" + blob);
                if (!response.path("encoding").asString().equals("base64"))
                    throw new IllegalStateException();
                byte[] bytes = Base64.getMimeDecoder().decode(response.path("content").asString());
                if (bytes.length > 32768) throw new IllegalStateException();
                var digest = MessageDigest.getInstance("SHA-1");
                digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
                if (!HexFormat.of().formatHex(digest.digest()).equals(blob))
                    throw new IllegalStateException();
                files.put(
                        path,
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(java.nio.ByteBuffer.wrap(bytes))
                                .toString());
            }
            RepositoryWorkspace.validateFiles(files, request.testClass());
            var repository =
                    workspace.create(
                            request.repository().substring(request.repository().indexOf('/') + 1),
                            files,
                            request.testClass(),
                            "github:" + request.repository() + "@" + sha);
            var immutable = new TreeMap<String, String>();
            files.forEach(
                    (path, content) -> {
                        if (!path.startsWith("src/main/")) immutable.put(path, content);
                    });
            var provenance =
                    new Provenance(
                            repository.id(),
                            request.repository(),
                            request.ref(),
                            sha,
                            request.subdirectory(),
                            BuildProfile.name(files),
                            IntentCompiler.digest(
                                    new WorkbenchConfiguration()
                                            .jsonMapper()
                                            .writeValueAsString(immutable)),
                            clock.instant());
            store.put("github-provenance", repository.id(), provenance);
            return repository;
        } catch (PlatformException e) {
            throw e;
        } catch (Exception invalid) {
            throw new PlatformException(
                    502,
                    "INVALID_GITHUB_SNAPSHOT",
                    "The pinned GitHub snapshot failed integrity checks.");
        } finally {
            slot.release();
        }
    }
}
