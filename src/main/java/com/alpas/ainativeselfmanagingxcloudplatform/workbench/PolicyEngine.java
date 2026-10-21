package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import org.springframework.stereotype.Component;

import java.util.List;

/** Policy is code, versioned independently of any model or prompt. */
@Component
public class PolicyEngine {
    public static final String VERSION = "sandbox-policy-v1";

    public PolicyReport evaluate(IntentSpec spec) {
        boolean production = "production".equals(spec.tier());
        boolean pii = "pii".equals(spec.dataClassification());
        var checks =
                List.of(
                        new PolicyCheck(
                                "RESIDENCY",
                                "region",
                                !pii || List.of("germany", "eu").contains(spec.region()),
                                "PII must remain in Germany or the EU."),
                        new PolicyCheck(
                                "PRIVATE_NETWORK",
                                "privateNetwork",
                                !production || spec.privateNetwork(),
                                "Production workloads require private networking."),
                        new PolicyCheck(
                                "RESTRICTED_EGRESS",
                                "restrictedEgress",
                                !production || spec.restrictedEgress(),
                                "Production workloads require restricted egress."),
                        new PolicyCheck(
                                "ENCRYPTION",
                                "encryptedStorage",
                                !pii || spec.encryptedStorage(),
                                "PII storage must require encryption."),
                        new PolicyCheck(
                                "AVAILABILITY",
                                "zones",
                                !production || (spec.zones() >= 3 && spec.replicas() >= 3),
                                "Production requires at least three zones and three replicas."),
                        new PolicyCheck(
                                "MONITORING",
                                "monitoring",
                                !production || spec.monitoring(),
                                "Production monitoring must be enabled."),
                        new PolicyCheck(
                                "ROLLBACK",
                                "rollback",
                                !production || spec.rollback(),
                                "Production rollback support must be enabled."),
                        new PolicyCheck(
                                "REPLICA_BUDGET",
                                "replicas",
                                spec.replicas() <= 10,
                                "The sandbox quota permits at most ten replicas."));
        return new PolicyReport(
                VERSION, checks.stream().allMatch(PolicyCheck::passed) ? "ALLOW" : "DENY", checks);
    }
}
