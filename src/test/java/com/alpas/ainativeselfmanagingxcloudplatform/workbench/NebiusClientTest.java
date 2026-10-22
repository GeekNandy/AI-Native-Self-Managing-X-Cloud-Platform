package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class NebiusClientTest {
    private final JsonMapper json = new WorkbenchConfiguration().jsonMapper();

    @Test
    void sendsBoundedSchemaRequestAndAcceptsOnlyCompleteJson() throws Exception {
        var captured = new AtomicReference<String>();
        var reply =
                new AtomicReference<>(
                        "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"answer\\\":true}\"}}]}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    captured.set(
                            new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] out = reply.get().getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, out.length);
                    e.getResponseBody().write(out);
                    e.close();
                });
        server.start();
        try {
            var client =
                    new NebiusClient(
                            "nvidia/Nemotron-3.5-Super-VL",
                            "test-key",
                            json,
                            HttpClient.newHttpClient(),
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            assertTrue(
                    client.complete(
                                    "Extract",
                                    "input",
                                    Map.of("type", "object"),
                                    2048,
                                    Duration.ofSeconds(2))
                            .path("answer")
                            .asBoolean());
            var sent = json.readTree(captured.get());
            assertEquals("json_schema", sent.path("response_format").path("type").asText());
            assertTrue(sent.path("response_format").path("json_schema").path("strict").asBoolean());
            assertEquals(2048, sent.path("max_tokens").asInt());
            assertFalse(sent.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
            assertFalse(captured.get().contains("test-key"));
            var catalog = new ScenarioCatalog(json);
            var spec = catalog.examples().getFirst().spec();
            var completeEnvelope =
                    json.writeValueAsString(Map.of("intent", spec, "questions", List.of()));
            reply.set(
                    json.writeValueAsString(
                            Map.of(
                                    "choices",
                                    List.of(
                                            Map.of(
                                                    "finish_reason",
                                                    "stop",
                                                    "message",
                                                    Map.of("content", completeEnvelope))))));
            var interpreter = new ConfiguredInterpreter("nebius", "", "", catalog, json, client);
            assertEquals(spec, interpreter.interpret("Create the fully specified service."));
            var unclear = new LinkedHashMap<String, Object>();
            unclear.put("intent", null);
            unclear.put("questions", List.of("Which region?"));
            reply.set(
                    json.writeValueAsString(
                            Map.of(
                                    "choices",
                                    List.of(
                                            Map.of(
                                                    "finish_reason",
                                                    "stop",
                                                    "message",
                                                    Map.of(
                                                            "content",
                                                            json.writeValueAsString(unclear)))))));
            assertThrows(PlatformException.class, () -> interpreter.interpret("Create something."));
            reply.set(
                    "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}");
            assertThrows(
                    PlatformException.class,
                    () ->
                            client.complete(
                                    "Extract", "input", Map.of(), 2048, Duration.ofSeconds(2)));
            reply.set(
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"not"
                            + " json\"}}]}");
            assertThrows(
                    PlatformException.class,
                    () ->
                            client.complete(
                                    "Extract", "input", Map.of(), 2048, Duration.ofSeconds(2)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingCredentialsAndOversizedInputsNeverCallTheProvider() {
        var empty = new NebiusClient("nvidia/Nemotron-3.5-Super-VL", "", json);
        assertFalse(empty.configured());
        assertThrows(
                PlatformException.class,
                () -> empty.complete("x", "x", Map.of(), 2048, Duration.ofSeconds(1)));
        var configured = new NebiusClient("nvidia/Nemotron-3.5-Super-VL", "test-key", json);
        assertThrows(
                PlatformException.class,
                () ->
                        configured.complete(
                                "x", "x".repeat(240001), Map.of(), 2048, Duration.ofSeconds(1)));
    }
}
