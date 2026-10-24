package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CodeModel {
    private CodeModel() {}

    public record Repository(
            String id,
            String name,
            String testClass,
            String head,
            String origin,
            Instant createdAt) {}

    public record Task(
            String id,
            String name,
            String instruction,
            Map<String, String> files,
            String testClass) {}

    public record Step(int sequence, String tool, String outcome, long durationMs, String output) {}

    public record TestResult(
            String status,
            int exitCode,
            boolean timedOut,
            boolean truncated,
            long durationMs,
            String output) {
        public boolean passed() {
            return status.equals("PASS");
        }
    }

    public record InferenceUsage(
            String provider,
            String requestedModel,
            String reportedModel,
            long inputTokens,
            long outputTokens,
            long totalTokens) {}

    public record Change(String path, String beforeDigest, String content) {}

    public record RepairRun(
            String id,
            String repositoryId,
            String baseCommit,
            String instruction,
            String provider,
            String policyVersion,
            String status,
            int maxAttempts,
            int timeoutSeconds,
            int attempts,
            Instant createdAt,
            Instant completedAt,
            List<Step> steps,
            TestResult baseline,
            TestResult verification,
            List<Change> changes,
            String patch,
            String digest,
            String mutationKind,
            String approvedCommit,
            String detail) {}

    public record RepairPolicy(
            String version, List<String> mutationOrder, List<String> trainingRunIds) {}

    public record Trial(
            String taskId,
            String variant,
            String policyVersion,
            String outcome,
            int attempts,
            long durationMs,
            String patchDigest) {}

    public record Comparison(
            String id,
            String status,
            Instant createdAt,
            RepairPolicy baseline,
            RepairPolicy candidate,
            List<Trial> trials,
            Map<String, Boolean> safety,
            boolean promotable,
            String detail) {}
}
