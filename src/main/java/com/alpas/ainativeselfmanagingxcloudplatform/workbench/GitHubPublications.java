package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Digest-bound publication journal. Remote branches and pull requests are reconciled on retries.
 */
@Service
public final class GitHubPublications {
    public record Preview(
            String runId,
            String destination,
            String baseBranch,
            String pinnedBase,
            String headBranch,
            String patchDigest,
            String approvalDigest,
            boolean enabled) {}

    public record Publication(
            String runId,
            String approvalDigest,
            String destination,
            String baseBranch,
            String pinnedBase,
            String headBranch,
            String tree,
            String commit,
            String status,
            String pullRequestUrl,
            Instant updatedAt) {}

    private final GitHubApi github;
    private final GitHubRepositories imports;
    private final RepairService repairs;
    private final RepositoryWorkspace repositories;
    private final StateStore store;
    private final Clock clock;
    private final String destination, baseBranch;

    public GitHubPublications(
            GitHubApi github,
            GitHubRepositories imports,
            RepairService repairs,
            RepositoryWorkspace repositories,
            StateStore store,
            Clock clock,
            @Value("${platform.github.publish-repository:}") String destination,
            @Value("${platform.github.publish-branch:feature}") String baseBranch) {
        this.github = github;
        this.imports = imports;
        this.repairs = repairs;
        this.repositories = repositories;
        this.store = store;
        this.clock = clock;
        this.destination = destination;
        this.baseBranch = baseBranch;
        if (!destination.isEmpty()
                && !destination.matches(
                        "[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
            throw new IllegalArgumentException("Invalid publication repository");
        if (!baseBranch.matches("[A-Za-z0-9][A-Za-z0-9_/-]{0,99}"))
            throw new IllegalArgumentException("Invalid publication branch");
    }

    public Map<String, Object> config() {
        return Map.of(
                "enabled",
                github.canWrite() && !destination.isBlank(),
                "repository",
                destination,
                "baseBranch",
                baseBranch,
                "draft",
                true);
    }

    public Publication get(String id) {
        repairs.require(id);
        return store.all("github-publications", Publication.class).stream()
                .filter(p -> p.runId().equals(id))
                .findFirst()
                .orElse(null);
    }

    public Preview preview(String id) {
        var run = repairs.require(id);
        var source = imports.provenance(run.repositoryId());
        if (!run.status().equals("APPROVED")
                || source == null
                || !source.repository().equals(destination))
            throw new PlatformException(
                    409,
                    "PUBLICATION_SOURCE_REQUIRED",
                    "Publish an approved repair from the configured GitHub destination.");
        if (!run.baseCommit().equals(repositories.initialCommit(run.repositoryId())))
            throw new PlatformException(
                    409,
                    "PUBLICATION_BASE_UNSUPPORTED",
                    "Publication requires a repair directly against its imported revision. Import a"
                            + " fresh revision for a subsequent repair.");
        String head = "workbench/repair-" + run.id();
        String digest =
                IntentCompiler.digest(
                        new WorkbenchConfiguration()
                                .jsonMapper()
                                .writeValueAsString(
                                        List.of(
                                                "draft-publication-v1",
                                                run.id(),
                                                run.digest(),
                                                source,
                                                destination,
                                                baseBranch,
                                                head)));
        return new Preview(
                run.id(),
                destination,
                baseBranch,
                source.pinnedCommit(),
                head,
                run.digest(),
                digest,
                github.canWrite() && !destination.isBlank());
    }

    public synchronized Publication publish(String id, String approvalDigest) {
        store.requireAvailable();
        var preview = preview(id);
        if (!preview.enabled())
            throw new PlatformException(
                    503,
                    "PUBLICATION_NOT_CONFIGURED",
                    "Configure a private GitHub token with contents and pull-request write access"
                            + " and a fixed destination.");
        if (!preview.approvalDigest().equals(approvalDigest))
            throw new PlatformException(
                    409,
                    "DIGEST_MISMATCH",
                    "Approve the exact destination, pinned base and tested patch.");
        var existing = get(id);
        if (existing != null && !existing.approvalDigest().equals(approvalDigest))
            throw new PlatformException(
                    409,
                    "PUBLICATION_CONFLICT",
                    "The publication journal belongs to a different approval.");
        if (existing != null && existing.status().equals("PR_CREATED")) return existing;
        String api = "/repos/" + destination;
        var base = github.get(api + "/commits/" + GitHubApi.segment(baseBranch));
        if (!base.path("sha").asString().equals(preview.pinnedBase()))
            throw new PlatformException(
                    409,
                    "STALE_GITHUB_BASE",
                    "GitHub base moved since import. Import and verify the new revision.");
        var source = imports.provenance(repairs.require(id).repositoryId());
        var entries =
                repairs.require(id).changes().stream()
                        .map(
                                c ->
                                        Map.of(
                                                "path",
                                                (source.subdirectory().isEmpty()
                                                                ? ""
                                                                : source.subdirectory() + "/")
                                                        + c.path(),
                                                "mode",
                                                "100644",
                                                "type",
                                                "blob",
                                                "content",
                                                c.content()))
                        .toList();
        save(preview, "", "", "REQUESTED", "");
        store.requireAvailable();
        String tree =
                github.post(
                                api + "/git/trees",
                                Map.of(
                                        "base_tree",
                                        base.path("commit").path("tree").path("sha").asString(),
                                        "tree",
                                        entries))
                        .path("sha")
                        .asString();
        if (!tree.matches("[a-f0-9]{40}"))
            throw new PlatformException(
                    502, "INVALID_GITHUB_RESPONSE", "GitHub returned an invalid tree identifier.");
        String refPath = api + "/git/ref/heads/" + preview.headBranch();
        String commit = null;
        try {
            commit = github.get(refPath).path("object").path("sha").asString();
        } catch (PlatformException missing) {
            if (missing.status() != 404) throw missing;
        }
        if (commit == null) {
            store.requireAvailable();
            commit =
                    github.post(
                                    api + "/git/commits",
                                    Map.of(
                                            "message",
                                            "Fix verified Java regression\n\nRepair-Digest: "
                                                    + preview.patchDigest(),
                                            "tree",
                                            tree,
                                            "parents",
                                            List.of(preview.pinnedBase())))
                            .path("sha")
                            .asString();
            if (!commit.matches("[a-f0-9]{40}"))
                throw new PlatformException(
                        502,
                        "INVALID_GITHUB_RESPONSE",
                        "GitHub returned an invalid commit identifier.");
            save(preview, tree, commit, "COMMIT_CREATED", "");
            store.requireAvailable();
            try {
                github.post(
                        api + "/git/refs",
                        Map.of("ref", "refs/heads/" + preview.headBranch(), "sha", commit));
            } catch (PlatformException uncertain) {
                commit = github.get(refPath).path("object").path("sha").asString();
            }
        }
        var observed = github.get(api + "/git/commits/" + commit);
        if (!observed.path("tree").path("sha").asString().equals(tree)
                || observed.path("parents").size() != 1
                || !observed.path("parents")
                        .path(0)
                        .path("sha")
                        .asString()
                        .equals(preview.pinnedBase())
                || !observed.path("message")
                        .asString()
                        .equals(
                                "Fix verified Java regression\n\nRepair-Digest: "
                                        + preview.patchDigest()))
            throw new PlatformException(
                    409,
                    "PUBLICATION_BRANCH_CONFLICT",
                    "The deterministic repair branch contains a different commit.");
        save(preview, tree, commit, "BRANCH_CREATED", "");
        String headOwner = destination.substring(0, destination.indexOf('/'));
        var prs =
                github.get(
                        api
                                + "/pulls?state=all&head="
                                + GitHubApi.segment(headOwner + ":" + preview.headBranch())
                                + "&base="
                                + GitHubApi.segment(baseBranch)
                                + "&per_page=100");
        long number = 0;
        for (var pr : prs)
            if (pr.path("head").path("ref").asString().equals(preview.headBranch())
                    && pr.path("base").path("ref").asString().equals(baseBranch)) {
                number = pr.path("number").asLong(0);
                break;
            }
        if (number == 0) {
            store.requireAvailable();
            var pr =
                    github.post(
                            api + "/pulls",
                            Map.of(
                                    "title",
                                    "Fix verified Java regression",
                                    "head",
                                    preview.headBranch(),
                                    "base",
                                    baseBranch,
                                    "draft",
                                    true,
                                    "body",
                                    "The source repair passes the unchanged regression suite.\n\n"
                                            + "Baseline: FAIL. Verification: PASS.\n\n"
                                            + "Repair digest: `"
                                            + preview.patchDigest()
                                            + "`\nPinned source: `"
                                            + preview.pinnedBase()
                                            + "`\n\n"
                                            + "Review the source diff and test evidence before"
                                            + " merging."));
            number = pr.path("number").asLong(0);
        }
        if (number < 1)
            throw new PlatformException(
                    502,
                    "INVALID_GITHUB_RESPONSE",
                    "GitHub did not return a pull-request number. Retry the same approval.");
        return save(
                preview,
                tree,
                commit,
                "PR_CREATED",
                "https://github.com/" + destination + "/pull/" + number);
    }

    private Publication save(Preview p, String tree, String commit, String status, String url) {
        var publication =
                new Publication(
                        p.runId(),
                        p.approvalDigest(),
                        p.destination(),
                        p.baseBranch(),
                        p.pinnedBase(),
                        p.headBranch(),
                        tree,
                        commit,
                        status,
                        url,
                        clock.instant());
        store.put("github-publications", p.runId(), publication);
        return publication;
    }
}
