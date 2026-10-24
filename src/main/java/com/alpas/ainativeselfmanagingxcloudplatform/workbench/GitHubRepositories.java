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

    @org.springframework.beans.factory.annotation.Value(
            "${platform.github.public-fallback-repository:GeekNandy/AI-Native-Self-Managing-X-Cloud-Platform}")
    private String publicFallbackRepository = "GeekNandy/AI-Native-Self-Managing-X-Cloud-Platform";

    private final CommandRunner commands;
    private final Semaphore slot = new Semaphore(1);

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubRepositories(
            RepositoryWorkspace workspace, GitHubApi github, StateStore store, Clock clock) {
        this(workspace, github, store, clock, new CommandRunner());
    }

    GitHubRepositories(
            RepositoryWorkspace workspace,
            GitHubApi github,
            StateStore store,
            Clock clock,
            CommandRunner commands) {
        this.workspace = workspace;
        this.github = github;
        this.store = store;
        this.clock = clock;
        this.commands = commands;
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
            if (request.repository().equals(publicFallbackRepository)
                    && !github.canWrite()
                    && (e.code().equals("GITHUB_RATE_LIMITED")
                            || e.getMessage().contains("HTTP 403")))
                return publicGit(request, deadline);
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

    private String git(java.nio.file.Path directory, long deadline, String... args) {
        long left = deadline - System.nanoTime();
        if (left <= 0)
            throw new PlatformException(
                    422, "IMPORT_DEADLINE", "Repository import exceeded 90 seconds.");
        var result =
                commands.run(
                        directory,
                        Duration.ofNanos(Math.min(left, Duration.ofSeconds(30).toNanos())),
                        List.of(args));
        if (result.exitCode() != 0 || result.timedOut() || result.truncated())
            throw new PlatformException(
                    502,
                    "PUBLIC_GIT_IMPORT_FAILED",
                    "The bounded public Git transport failed. Check revision, repository size and"
                            + " network access, or configure a private GitHub read credential.");
        return result.output().stripTrailing();
    }

    private Repository publicGit(Import request, long deadline) {
        java.nio.file.Path temporary = null;
        try {
            temporary = java.nio.file.Files.createTempDirectory("xcloud-public-git-");
            git(
                    temporary,
                    deadline,
                    "git",
                    "-c",
                    "core.hooksPath=/dev/null",
                    "init",
                    "--template=",
                    "--initial-branch=snapshot");
            git(
                    temporary,
                    deadline,
                    "git",
                    "remote",
                    "add",
                    "origin",
                    "https://github.com/" + request.repository() + ".git");
            git(temporary, deadline, "git", "config", "remote.origin.promisor", "true");
            git(
                    temporary,
                    deadline,
                    "git",
                    "config",
                    "remote.origin.partialclonefilter",
                    "blob:limit=32769");
            git(
                    temporary,
                    deadline,
                    "git",
                    "-c",
                    "core.hooksPath=/dev/null",
                    "-c",
                    "protocol.file.allow=never",
                    "-c",
                    "protocol.ext.allow=never",
                    "fetch",
                    "--depth=1",
                    "--filter=blob:limit=32769",
                    "--no-tags",
                    "--no-recurse-submodules",
                    "origin",
                    request.ref());
            String sha = git(temporary, deadline, "git", "rev-parse", "FETCH_HEAD");
            if (!sha.matches("[a-f0-9]{40}")) throw new IllegalStateException();
            String tree =
                    git(
                            temporary,
                            deadline,
                            "git",
                            "-c",
                            "core.quotePath=false",
                            "ls-tree",
                            "-r",
                            "--full-tree",
                            "FETCH_HEAD");
            String prefix = request.subdirectory().isEmpty() ? "" : request.subdirectory() + "/";
            var files = new TreeMap<String, String>();
            for (String line : tree.split("\n")) {
                var columns = line.split("\t", 2);
                if (columns.length != 2 || !columns[1].startsWith(prefix)) continue;
                String path = columns[1].substring(prefix.length());
                var metadata = columns[0].split(" ");
                if (metadata[0].equals("120000") || metadata[0].equals("160000"))
                    throw new PlatformException(
                            422,
                            "UNSUPPORTED_REPOSITORY",
                            "Symlinks and submodules are not accepted in the selected project.");
                if (!(path.equals("pom.xml") || path.matches("src/(main|test)/java/.+\\.java")))
                    continue;
                if (files.size() >= 25 || !metadata[2].matches("[a-f0-9]{40}"))
                    throw new PlatformException(
                            422,
                            "REPOSITORY_TOO_LARGE",
                            "Import exceeds the source snapshot budget.");
                // Blob output is bounded by CommandRunner. Preserve trailing whitespace for Git
                // hash verification.
                var result =
                        commands.run(
                                temporary,
                                Duration.ofNanos(
                                        Math.max(
                                                1,
                                                Math.min(
                                                        deadline - System.nanoTime(),
                                                        Duration.ofSeconds(15).toNanos()))),
                                List.of("git", "show", metadata[2]));
                if (result.exitCode() != 0 || result.timedOut() || result.truncated())
                    throw new IllegalStateException();
                String content = result.output();
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                var hash = MessageDigest.getInstance("SHA-1");
                hash.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
                hash.update(bytes);
                if (!HexFormat.of().formatHex(hash.digest()).equals(metadata[2]))
                    throw new IllegalStateException();
                files.put(path, content);
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
            store.put(
                    "github-provenance",
                    repository.id(),
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
                            clock.instant()));
            return repository;
        } catch (PlatformException e) {
            throw e;
        } catch (Exception failure) {
            throw new PlatformException(
                    502,
                    "PUBLIC_GIT_IMPORT_FAILED",
                    "Public Git snapshot failed integrity checks.");
        } finally {
            if (temporary != null)
                try (var paths = java.nio.file.Files.walk(temporary)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                        java.nio.file.Files.deleteIfExists(path);
                } catch (Exception cleanup) {
                    /* No sources are executed in this disposable transport directory. */
                }
        }
    }
}
