package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Pull-only agent. Test containers receive neither HTTP credentials nor the Docker socket. */
public final class WorkerAgent {
    private final JsonMapper json = new WorkbenchConfiguration().jsonMapper();
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final URI origin;
    private String credential;
    private Instant credentialExpiry = Instant.EPOCH;
    private final String instance = UUID.randomUUID().toString();
    private Instant acceptingUntil = Instant.MAX;
    private volatile boolean stopping;
    private volatile Future<?> currentExecution;

    private WorkerAgent(String endpoint) {
        origin = URI.create(endpoint);
        if (origin.getHost() == null
                || origin.getUserInfo() != null
                || origin.getQuery() != null
                || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                || !(origin.getScheme().equals("https")
                        || (origin.getScheme().equals("http")
                                && Set.of("localhost", "127.0.0.1").contains(origin.getHost()))))
            throw new IllegalArgumentException(
                    "Worker endpoint must be an HTTPS origin or local test origin");
    }

    public static boolean enabled() {
        return "worker".equals(System.getenv("PLATFORM_NODE_ROLE"));
    }

    public static void run() {
        try {
            new WorkerAgent(
                            Objects.requireNonNull(
                                    System.getenv("PLATFORM_COORDINATOR_URL"),
                                    "Coordinator URL is required"))
                    .loop();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Worker stopped; check coordinator access and Docker availability.");
        }
    }

    private synchronized String credential() throws Exception {
        String configured = System.getenv("PLATFORM_WORKER_TOKEN");
        if (configured != null && !configured.isBlank()) return configured;
        if (clockNow().plusSeconds(60).isBefore(credentialExpiry)) return credential;
        String endpoint = System.getenv("ACTIONS_ID_TOKEN_REQUEST_URL"),
                requestToken = System.getenv("ACTIONS_ID_TOKEN_REQUEST_TOKEN");
        if (endpoint == null || requestToken == null)
            throw new IllegalStateException("Worker authentication is not configured");
        var url = URI.create(endpoint);
        if (!"https".equals(url.getScheme())
                || url.getHost() == null
                || !url.getHost().endsWith(".actions.githubusercontent.com")
                || url.getUserInfo() != null)
            throw new IllegalStateException("Invalid OIDC endpoint");
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        endpoint
                                                + (endpoint.contains("?") ? "&" : "?")
                                                + "audience="
                                                + URLEncoder.encode(
                                                        origin.toString().replaceAll("/$", ""),
                                                        StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(8))
                        .header("Authorization", "Bearer " + requestToken)
                        .GET()
                        .build();
        var response = BoundedHttp.send(http, request, 32768, Duration.ofSeconds(8));
        if (response.statusCode() != 200)
            throw new IllegalStateException("OIDC credential request failed");
        credential = json.readTree(response.body()).path("value").asString();
        var claims = json.readTree(Base64.getUrlDecoder().decode(credential.split("\\.")[1]));
        credentialExpiry = Instant.ofEpochSecond(claims.path("exp").asLong(0));
        return credential;
    }

    private Instant clockNow() {
        return Instant.now();
    }

    private JsonNode post(String path, Object body) throws Exception {
        var request =
                HttpRequest.newBuilder(origin.resolve(path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + credential())
                        .header("X-Worker-Instance", instance)
                        .header("X-Worker-Deadline", acceptingUntil.toString())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build();
        var response = BoundedHttp.send(http, request, 300000, Duration.ofSeconds(10));
        if (response.statusCode() != 200)
            throw new IllegalStateException(
                    "Coordinator rejected worker request (" + response.statusCode() + ")");
        return json.readTree(response.body());
    }

    private void loop() throws Exception {
        String image =
                System.getenv().getOrDefault("PLATFORM_SANDBOX_IMAGE", "eclipse-temurin:21-jdk");
        int seconds =
                Integer.parseInt(System.getenv().getOrDefault("PLATFORM_WORKER_SECONDS", "1200"));
        if (seconds < 10 || seconds > 18000)
            throw new IllegalArgumentException("Worker lifetime must be 10–18000 seconds");
        acceptingUntil = clockNow().plusSeconds(seconds);
        var directory =
                java.nio.file.Path.of(
                                System.getenv()
                                        .getOrDefault(
                                                "PLATFORM_WORKER_DATA_DIR", ".workbench/worker"))
                        .toAbsolutePath()
                        .normalize();
        Files.createDirectories(directory);
        try (var state = new StateStore(json, directory.toString());
                var tasks = Executors.newVirtualThreadPerTaskExecutor();
                var beats = Executors.newSingleThreadScheduledExecutor()) {
            var sandbox = new DockerSandbox(new CommandRunner(), image, state);
            if (!Boolean.TRUE.equals(sandbox.capabilities().get("available")))
                throw new IllegalStateException("Preload the configured Docker image");
            var cleanup = new Thread(() -> {
                stopping = true;
                var execution = currentExecution;
                if (execution != null) execution.cancel(true);
                sandbox.cleanupOwned();
            }, "worker-cleanup");
            Runtime.getRuntime().addShutdownHook(cleanup);
            try {
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            int failures = 0;
            while (!stopping && System.nanoTime() < stop && !Thread.currentThread().isInterrupted()) {
                JsonNode response;
                try {
                    response = post("/api/worker/claim", new WorkerController.Hello(image));
                    failures = 0;
                } catch (Exception unavailable) {
                    if (++failures > 150) throw unavailable;
                    Thread.sleep(2000);
                    continue;
                }
                if (!response.path("available").asBoolean()) {
                    Thread.sleep(1500);
                    continue;
                }
                var lease = json.treeToValue(response.path("lease"), RemoteJobBroker.Lease.class);
                var budget = Duration.between(clockNow(), lease.deadline());
                if (budget.isNegative() || budget.isZero()) continue;
                var execution =
                        tasks.submit(() -> sandbox.test(lease.files(), lease.testClass(), budget));
                currentExecution = execution;
                if (stopping) execution.cancel(true);
                var heartbeat =
                        beats.scheduleAtFixedRate(
                                () -> {
                                    try {
                                        post(
                                                "/api/worker/jobs/" + lease.id() + "/heartbeat",
                                                new WorkerController.Heartbeat(lease.fence()));
                                    } catch (Exception ended) {
                                        execution.cancel(true);
                                    }
                                },
                                5,
                                5,
                                TimeUnit.SECONDS);
                TestResult result;
                try {
                    result = execution.get(Math.max(1, budget.toMillis()), TimeUnit.MILLISECONDS);
                } catch (Exception failed) {
                    execution.cancel(true);
                    result =
                            new TestResult(
                                    "ERROR",
                                    -1,
                                    false,
                                    false,
                                    0,
                                    "Worker execution was cancelled or exceeded its deadline.");
                } finally {
                    currentExecution = null;
                    heartbeat.cancel(false);
                }
                var completed =
                        new RemoteJobBroker.Completion(lease.fence(), lease.inputDigest(), result);
                for (int retry = 0; retry < 3; retry++) {
                    try {
                        post("/api/worker/jobs/" + lease.id() + "/complete", completed);
                        break;
                    } catch (Exception rejected) {
                        if (retry < 2) Thread.sleep(500);
                    }
                }
            }
            } finally {
                stopping = true;
                var execution = currentExecution;
                if (execution != null) execution.cancel(true);
                sandbox.cleanupOwned();
                try {
                    Runtime.getRuntime().removeShutdownHook(cleanup);
                } catch (IllegalStateException shuttingDown) {
                    // The shutdown hook performs the same owner-scoped cleanup.
                }
            }
        }
    }
}
