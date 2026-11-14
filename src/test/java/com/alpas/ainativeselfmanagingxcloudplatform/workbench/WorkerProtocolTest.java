package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Actual HTTP protocol with a simulated worker result; does not claim container execution. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "platform.ai.provider=fixture",
            "platform.worker.token=test-worker-token-012345678901234567890123",
            "platform.data-dir=memory",
            "platform.auth.operator-token=test-operator-token-0123456789",
            "platform.auth.agent-token=test-agent-token-0123456789"
        })
@org.junit.jupiter.api.Timeout(30)
class WorkerProtocolTest {
    private static final String WORKER = "test-worker-token-012345678901234567890123";
    private static final String OPERATOR = "test-operator-token-0123456789";

    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired RemoteJobBroker jobs;
    final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> request(String path, Object body, String credential)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (credential != null) request.header("Authorization", "Bearer " + credential);
        if (WORKER.equals(credential)) request.header("X-Worker-Instance", "epoch-protocol-test");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode claim() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var response = request("/api/worker/claim", Map.of("image", "eclipse-temurin:21-jdk"), WORKER);
            assertEquals(200, response.statusCode(), response.body());
            var claimed = json.readTree(response.body());
            if (claimed.path("available").asBoolean()) return claimed.path("lease");
            Thread.sleep(10);
        }
        throw new AssertionError("Execution was not offered to the authenticated worker");
    }

    @Test
    void epochsAreRequiredOverHttpAndReceiptsExcludeExecutionInputsAndLeaseTokens() throws Exception {
        var files = Map.of(
                "src/main/java/Example.java", "class Example { int privateSourceMarker; }",
                "src/test/java/ExampleTest.java", "class ExampleTest { public static void main(String[] args) {} }");
        var passed = new CodeModel.TestResult("PASS", 0, false, false, 1, "protocol fixture");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var awaiting = executor.submit(() -> jobs.test(files, "ExampleTest", Duration.ofSeconds(25)));
            try {
                var lease = claim();
                long epoch = lease.path("leaderEpoch").asLong();
                assertTrue(epoch > 0, lease.toString());
                String id = lease.path("id").asString();
                String fence = lease.path("fence").asString();
                String workerPath = "/api/worker/jobs/" + id;
                assertEquals(400, request(workerPath + "/heartbeat", Map.of("fence", fence), WORKER).statusCode());
                assertEquals(400, request(workerPath + "/heartbeat", Map.of("fence", fence, "leaderEpoch", 0), WORKER).statusCode());
                assertEquals(409, request(workerPath + "/heartbeat", Map.of("fence", fence, "leaderEpoch", epoch + 1), WORKER).statusCode());
                var renewed = request(workerPath + "/heartbeat", Map.of("fence", fence, "leaderEpoch", epoch), WORKER);
                assertEquals(200, renewed.statusCode(), renewed.body());

                var missingEpoch = Map.of("fence", fence, "inputDigest", lease.path("inputDigest").asString(), "result", passed);
                assertEquals(400, request(workerPath + "/complete", missingEpoch, WORKER).statusCode());
                var stale = new RemoteJobBroker.Completion(fence, lease.path("inputDigest").asString(), passed, epoch + 1);
                assertEquals(409, request(workerPath + "/complete", stale, WORKER).statusCode());
                var completion = new RemoteJobBroker.Completion(fence, lease.path("inputDigest").asString(), passed, epoch);
                var malformed = json.valueToTree(completion);
                ((tools.jackson.databind.node.ObjectNode) malformed.path("result")).putNull("status");
                assertEquals(400, request(workerPath + "/complete", malformed, WORKER).statusCode());
                for (int attempt = 0; attempt < 2; attempt++) {
                    var completed = request(workerPath + "/complete", completion, WORKER);
                    assertEquals(200, completed.statusCode(), completed.body());
                }
                assertEquals(passed, awaiting.get(2, TimeUnit.SECONDS));

                String receiptPath = "/api/code/workers/jobs/" + id;
                assertEquals(401, request(receiptPath, null, null).statusCode());
                assertEquals(401, request(receiptPath, null, WORKER).statusCode());
                var response = request(receiptPath, null, OPERATOR);
                assertEquals(200, response.statusCode(), response.body());
                var receipt = json.readTree(response.body());
                assertEquals(id, receipt.path("id").asString());
                assertEquals("COMPLETED", receipt.path("status").asString());
                assertEquals(epoch, receipt.path("leaderEpoch").asLong());
                assertEquals("PASS", receipt.path("result").path("status").asString());
                assertFalse(receipt.has("files"));
                assertFalse(receipt.has("fence"));
                assertFalse(response.body().contains(fence));
                assertFalse(response.body().contains("privateSourceMarker"));
                assertEquals(404, request("/api/code/workers/jobs/nonexistent", null, OPERATOR).statusCode());
            } finally {
                awaiting.cancel(true);
            }
        }
    }
}
