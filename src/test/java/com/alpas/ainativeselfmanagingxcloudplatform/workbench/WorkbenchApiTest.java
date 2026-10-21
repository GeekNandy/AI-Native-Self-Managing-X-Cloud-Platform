package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"platform.ai.provider=fixture", "platform.data-dir=memory", "platform.auth.operator-token=test-operator-token-0123456789", "platform.auth.agent-token=test-agent-token-0123456789"})
class WorkbenchApiTest {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired ScenarioCatalog catalog;
    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> request(String method, String path, Object body, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        builder.header("Authorization", "Bearer test-operator-token-0123456789");
        if (origin != null) builder.header("Origin", origin);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode post(String path, Object body) throws Exception {
        var response = request("POST", "/api" + path, body, null);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    @Test void completeWorkflowIncludesRecoveryFeedbackAndEvaluationOverHttp() throws Exception {
        var run = post("/runs", Map.of("prompt", catalog.examples().getFirst().prompt()));
        var plan = run.path("plan");
        assertEquals("AWAITING_APPROVAL", run.path("status").asText());
        String id = plan.path("id").asText();
        String environment = plan.path("environmentId").asText();
        var approval = Map.of("digest", plan.path("digest").asText(), "idempotencyKey", "http-provision-key");
        assertFalse(post("/plans/" + id + "/apply", approval).path("replayed").asBoolean());
        assertTrue(post("/plans/" + id + "/apply", approval).path("replayed").asBoolean());
        var drift = post("/environments/" + environment + "/faults", Map.of("kind", "replica-loss"));
        assertEquals("DRIFT_DETECTED", drift.path("convergence").asText());
        var recovery = post("/environments/" + environment + "/reconcile", Map.of());
        var recovered = post("/plans/" + recovery.path("id").asText() + "/apply", Map.of("digest", recovery.path("digest").asText(), "idempotencyKey", "http-recovery-key"));
        assertEquals("CONVERGED", recovered.path("receipt").path("environment").path("convergence").asText());
        post("/feedback", Map.of("runId", run.path("id").asText(), "note", "Production PII requirements remain governed during recovery.",
                "expectedSpec", plan.path("intent"), "expectedDecision", "ALLOW"));
        var evaluation = post("/evaluations", Map.of("mode", "control-plane"));
        assertEquals(evaluation.path("total").asInt(), evaluation.path("passed").asInt(), evaluation.toPrettyString());
        assertTrue(evaluation.path("feedbackCases").asInt() >= 1);
        assertTrue(evaluation.path("total").asInt() >= 22);
        var interpreter = post("/evaluations", Map.of("mode", "interpreter"));
        assertEquals(interpreter.path("total").asInt(), interpreter.path("passed").asInt(), interpreter.toPrettyString());
    }

    @Test void policyDenialIsVisibleAndEnforcedOverHttp() throws Exception {
        var run = post("/runs", Map.of("prompt", catalog.examples().get(1).prompt()));
        assertEquals("BLOCKED", run.path("status").asText());
        var plan = run.path("plan");
        var response = request("POST", "/api/plans/" + plan.path("id").asText() + "/apply",
                Map.of("digest", plan.path("digest").asText(), "idempotencyKey", "http-denied-key"), null);
        assertEquals(409, response.statusCode());
        assertEquals("POLICY_DENIED", json.readTree(response.body()).path("code").asText());
    }

    @Test void rejectsForeignOriginsAndIncompleteOrCoercedJson() throws Exception {
        assertEquals(403, request("POST", "/api/runs", Map.of("prompt", "hello"), "https://foreign.example").statusCode());
        assertEquals(400, request("POST", "/api/plans", Map.of("spec", Map.of("serviceName", "missing-fields")), null).statusCode());
        assertEquals(400, request("POST", "/api/runs", Map.of("prompt", "hello", "approved", true), null).statusCode());
        assertEquals(400, request("POST", "/api/runs", Map.of("prompt", " "), null).statusCode());
        assertEquals(200, request("GET", "/actuator/health", null, null).statusCode());
        var page = request("GET", "/", null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Review the proposal"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
    }
    @Test void scopesProtectOperatorActionsAndMcpExposesProposalsOnly() throws Exception {
        var anonymous=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/environments")).GET().build();
        assertEquals(401,http.send(anonymous,HttpResponse.BodyHandlers.ofString()).statusCode());
        var denied=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/feedback"))
                .header("Authorization","Bearer test-agent-token-0123456789").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403,http.send(denied,HttpResponse.BodyHandlers.ofString()).statusCode());
        var listed=mcp(Map.of("jsonrpc","2.0","id",1,"method","tools/list","params",Map.of()));
        assertEquals(200,listed.statusCode());var tools=json.readTree(listed.body()).path("result").path("tools");assertEquals(9,tools.size());
        for(var tool:tools) { assertFalse(tool.path("name").asText().contains("apply"));assertFalse(tool.path("name").asText().contains("approve")); }
        var call=mcp(Map.of("jsonrpc","2.0","id","p","method","tools/call","params",Map.of("name","propose_intent","arguments",Map.of("spec",catalog.examples().getFirst().spec()))));
        assertFalse(json.readTree(call.body()).path("result").path("isError").asBoolean());
        assertEquals("AWAITING_APPROVAL",json.readTree(call.body()).path("result").path("structuredContent").path("status").asText());
        var notification=mcp(Map.of("jsonrpc","2.0","method","notifications/initialized"));assertEquals(202,notification.statusCode());assertTrue(notification.body().isBlank());
        var unknown=mcp(Map.of("jsonrpc","2.0","id",2,"method","tools/call","params",Map.of("name","approve_plan","arguments",Map.of())));
        assertEquals(-32602,json.readTree(unknown.body()).path("error").path("code").asInt());
    }
    private HttpResponse<String> mcp(Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp"))
                .header("Authorization","Bearer test-agent-token-0123456789").header("Content-Type","application/json")
                .header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version",McpController.VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }

}
