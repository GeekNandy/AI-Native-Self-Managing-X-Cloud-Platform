package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import java.time.Instant;
import java.util.List;

/** Immutable contracts shared by the interpreter, control plane and workbench. */
public final class Model {
    private Model() {}

    public record IntentSpec(String serviceName, String runtime, String tier, String region,
                             boolean privateNetwork, boolean restrictedEgress,
                             String dataClassification, boolean encryptedStorage,
                             int zones, int replicas, boolean monitoring, boolean rollback) {
        public String environmentId() { return serviceName + "-" + tier; }
        public IntentSpec withReplicas(int value) {
            return new IntentSpec(serviceName, runtime, tier, region, privateNetwork, restrictedEgress,
                    dataClassification, encryptedStorage, zones, value, monitoring, rollback);
        }
        public IntentSpec withRestrictedEgress(boolean value) {
            return new IntentSpec(serviceName, runtime, tier, region, privateNetwork, value,
                    dataClassification, encryptedStorage, zones, replicas, monitoring, rollback);
        }
    }

    public record PolicyCheck(String id, String field, boolean passed, String requirement) {}
    public record PolicyReport(String version, String decision, List<PolicyCheck> checks) {
        public PolicyReport { checks = List.copyOf(checks); }
        public boolean allowed() { return "ALLOW".equals(decision); }
    }
    public record Artifact(String path, String language, String content) {}
    public record Action(int order, String resource, String operation, String description,
                         List<String> dependsOn) {
        public Action { dependsOn = List.copyOf(dependsOn); }
    }
    public record Plan(String id, String environmentId, String kind, String status,
                       long baseRevision, IntentSpec intent, PolicyReport policy,
                       List<Action> actions, List<Artifact> artifacts, String digest,
                       Instant createdAt, Instant expiresAt) {
        public Plan { actions = List.copyOf(actions); artifacts = List.copyOf(artifacts); }
    }
    public record Drift(String field, Object expected, Object observed, String severity) {}
    public record EnvironmentView(String id, long revision, long desiredVersion, String convergence,
                                  IntentSpec desired, IntentSpec observed, List<Drift> drift) {
        public EnvironmentView { drift = List.copyOf(drift); }
    }
    public record Receipt(String operationId, String planId, String digest, String idempotencyKey,
                          EnvironmentView environment, Instant completedAt) {}
    public record ApplyResult(Receipt receipt, boolean replayed) {}
    public record AuditEvent(long sequence, Instant timestamp, String type, String actor,
                             String environmentId, String correlationId, String detail) {}
    public record AgentRun(String id, Instant createdAt, String prompt, String provider,
                           String status, double interpretationMs, Plan plan, List<String> messages) {
        public AgentRun { messages = List.copyOf(messages); }
    }
    public record Scenario(String id, String name, String category, boolean fixture,
                           String prompt, IntentSpec spec, String expected) {}
    public record Feedback(String id, String runId, String note, IntentSpec expectedSpec,
                           String expectedDecision, Instant createdAt) {}
    public record EvaluationCase(String id, String name, String category, String expected,
                                 String actual, boolean passed, double latencyMs, String detail) {}
    public record EvaluationReport(String id, Instant createdAt, String mode, String datasetDigest,
                                   int passed, int total, double passRate, double p50Ms, double p95Ms,
                                   int feedbackCases, List<EvaluationCase> cases, String limitation) {
        public EvaluationReport { cases = List.copyOf(cases); }
    }
}
