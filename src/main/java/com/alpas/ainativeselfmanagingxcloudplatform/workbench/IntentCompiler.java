package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class IntentCompiler {
    private final PolicyEngine policy;
    private final JsonMapper json;

    public IntentCompiler(PolicyEngine policy, JsonMapper json) {
        this.policy = policy;
        this.json = json;
    }

    public PolicyReport analyze(IntentSpec spec) {
        validate(spec);
        return policy.evaluate(spec);
    }

    public void validate(IntentSpec spec) {
        var errors = new ArrayList<String>();
        if (spec == null)
            throw new PlatformException(
                    422, "INVALID_INTENT", "A complete structured intent is required.");
        if (spec.serviceName() == null
                || !spec.serviceName().matches("[a-z][a-z0-9-]{1,38}[a-z0-9]"))
            errors.add(
                    "serviceName must be 3-40 lowercase letters, digits or hyphens, starting with a"
                        + " letter.");
        if (!"java21".equals(spec.runtime()))
            errors.add("This slice supports runtime java21 only.");
        if (!List.of("development", "production").contains(String.valueOf(spec.tier())))
            errors.add("tier must be development or production.");
        if (!List.of("germany", "eu", "us", "local").contains(String.valueOf(spec.region())))
            errors.add("region must be germany, eu, us or local.");
        if (!List.of("public", "internal", "pii")
                .contains(String.valueOf(spec.dataClassification())))
            errors.add("dataClassification must be public, internal or pii.");
        if (spec.zones() < 1 || spec.zones() > 3) errors.add("zones must be between 1 and 3.");
        if (spec.replicas() < 1 || spec.replicas() > 20)
            errors.add("replicas must be between 1 and 20.");
        if (spec.replicas() < spec.zones()) errors.add("replicas must be at least zones.");
        if (!errors.isEmpty())
            throw new PlatformException(
                    422, "INVALID_INTENT", "Intent failed semantic validation.", errors);
    }

    public List<Action> actions(IntentSpec spec) {
        return List.of(
                new Action(
                        1,
                        "network",
                        "ENSURE",
                        "Declare network boundary and egress requirements",
                        List.of()),
                new Action(
                        2,
                        "data",
                        "ENSURE",
                        "Declare classification, residency and encryption requirements",
                        List.of("network")),
                new Action(
                        3,
                        "workload",
                        "ENSURE",
                        "Compile Java 21 workload with " + spec.replicas() + " replicas",
                        List.of("network", "data")),
                new Action(
                        4,
                        "operations",
                        "ENSURE",
                        "Declare monitoring and rollback requirements",
                        List.of("workload")));
    }

    public List<Artifact> artifacts(IntentSpec spec) {
        String name = spec.serviceName();
        String deployment =
                """
                # REVIEW PREVIEW. The workbench never applies this file to a cluster.
                # Residency and storage encryption require a provider adapter and are not enforced here.
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: %s
                spec:
                  replicas: %d
                  revisionHistoryLimit: %d
                  selector:
                    matchLabels: {app: %s}
                  template:
                    metadata:
                      labels: {app: %s}
                    spec:
                      automountServiceAccountToken: false
                      securityContext:
                        runAsNonRoot: true
                        runAsUser: 10001
                        seccompProfile: {type: RuntimeDefault}
                      topologySpreadConstraints:
                        - maxSkew: 1
                          minDomains: %d
                          topologyKey: topology.kubernetes.io/zone
                          whenUnsatisfiable: DoNotSchedule
                          labelSelector:
                            matchLabels: {app: %s}
                      containers:
                        - name: app
                          image: local/%s:demo
                          ports: [{containerPort: 8080}]
                          securityContext:
                            allowPrivilegeEscalation: false
                            readOnlyRootFilesystem: true
                            capabilities: {drop: [ALL]}
                          resources:
                            requests: {cpu: 100m, memory: 128Mi}
                            limits: {cpu: 500m, memory: 256Mi}
                          readinessProbe:
                            httpGet: {path: /health, port: 8080}
                          livenessProbe:
                            httpGet: {path: /health, port: 8080}
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: %s
                spec:
                  type: %s
                  selector: {app: %s}
                  ports: [{port: 80, targetPort: 8080}]
                """
                        .formatted(
                                name,
                                spec.replicas(),
                                spec.rollback() ? 5 : 0,
                                name,
                                name,
                                spec.zones(),
                                name,
                                name,
                                name,
                                spec.privateNetwork() ? "ClusterIP" : "LoadBalancer",
                                name);
        String network =
                """
                # Requires a NetworkPolicy-capable CNI. Restricted mode denies all egress.
                # Add reviewed DNS/service allow rules before deploying a real application.
                apiVersion: networking.k8s.io/v1
                kind: NetworkPolicy
                metadata:
                  name: %s
                spec:
                  podSelector:
                    matchLabels: {app: %s}
                  policyTypes: [Ingress, Egress]
                  ingress: [%s]
                  egress: [%s]
                """
                        .formatted(
                                name,
                                name,
                                spec.privateNetwork() ? "{from: [{podSelector: {}}]}" : "{}",
                                spec.restrictedEgress() ? "" : "{}");
        String main =
                """
                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.nio.charset.StandardCharsets;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        var server = HttpServer.create(new InetSocketAddress(8080), 0);
                        server.createContext("/", exchange -> {
                            var body = "{\\"service\\":\\"%s\\",\\"status\\":\\"ok\\"}".getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, body.length);
                            try (var output = exchange.getResponseBody()) { output.write(body); }
                        });
                        server.start();
                    }
                }
                """
                        .formatted(name);
        String dockerfile =
                """
                FROM eclipse-temurin:21-jdk AS build
                WORKDIR /src
                COPY Main.java .
                RUN javac --release 21 Main.java
                FROM eclipse-temurin:21-jre
                WORKDIR /app
                COPY --from=build /src/Main.class .
                USER 10001:10001
                EXPOSE 8080
                ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "Main"]
                """;
        return List.of(
                new Artifact(
                        "intent.json",
                        "json",
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(spec)),
                new Artifact("deployment.yaml", "yaml", deployment),
                new Artifact("network-policy.yaml", "yaml", network),
                new Artifact("Main.java", "java", main),
                new Artifact("Dockerfile", "dockerfile", dockerfile));
    }

    public String serialize(Object value) {
        return json.writeValueAsString(value);
    }

    public static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
