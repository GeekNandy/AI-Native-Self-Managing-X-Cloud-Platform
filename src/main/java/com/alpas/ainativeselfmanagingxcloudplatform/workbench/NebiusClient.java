package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Bounded, schema-constrained inference. The provider never receives execution authority. */
@Component
public final class NebiusClient {
    private static final URI ENDPOINT =
            URI.create("https://api.tokenfactory.nebius.com/v1/chat/completions");
    private final String model, key;
    private final JsonMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final Semaphore slots = new Semaphore(2);
    private final ThreadLocal<InferenceUsage> usage = new ThreadLocal<>();

    public InferenceUsage usage() {
        return usage.get();
    }

    @Autowired
    public NebiusClient(
            @Value("${platform.nebius.model:nvidia/Nemotron-3.5-Super-VL}") String model,
            @Value("${platform.nebius.api-key:}") String key,
            JsonMapper json) {
        this(
                model,
                key,
                json,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                ENDPOINT);
    }

    NebiusClient(String model, String key, JsonMapper json, HttpClient http, URI endpoint) {
        if (!model.matches("[A-Za-z0-9._/-]{1,140}"))
            throw new IllegalArgumentException("Set a valid NEBIUS_MODEL identifier.");
        this.model = model;
        this.key = key;
        this.json = json;
        this.http = http;
        this.endpoint = endpoint;
    }

    public boolean configured() {
        return !key.isBlank();
    }

    public String model() {
        return model;
    }

    public JsonNode complete(
            String system, String input, Map<String, Object> schema, int tokens, Duration budget) {
        usage.remove();
        if (!configured())
            throw new PlatformException(
                    503, "MODEL_NOT_CONFIGURED", "Set NEBIUS_API_KEY to use Nebius proposals.");
        if (input.length() > 240_000 || tokens < 1 || tokens > 4096)
            throw new PlatformException(
                    400,
                    "MODEL_INPUT_BUDGET",
                    "The request exceeds the bounded inference contract.");
        if (!slots.tryAcquire())
            throw new PlatformException(
                    429, "MODEL_BUSY", "Two Nebius calls are already in flight. Retry shortly.");
        try {
            var timeout = Duration.ofMillis(Math.max(1, Math.min(30_000, budget.toMillis())));
            var body = new LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("max_tokens", tokens);
            body.put(
                    "messages",
                    List.of(
                            Map.of(
                                    "role",
                                    "system",
                                    "content",
                                    system + " Return only JSON matching the supplied schema."),
                            Map.of("role", "user", "content", input)));
            body.put(
                    "response_format",
                    Map.of(
                            "type",
                            "json_schema",
                            "json_schema",
                            Map.of(
                                    "name",
                                    "workbench_proposal",
                                    "strict",
                                    true,
                                    "schema",
                                    schema)));
            if (model.startsWith("nvidia/Nemotron"))
                body.put("chat_template_kwargs", Map.of("enable_thinking", false));
            var request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + key)
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(body)))
                            .build();
            var response = BoundedHttp.send(http, request, 131_072, timeout);
            if (response.statusCode() != 200)
                throw new PlatformException(
                        503,
                        "MODEL_UNAVAILABLE",
                        "Nebius returned HTTP "
                                + response.statusCode()
                                + ". No change was executed.");
            var envelope = json.readTree(response.body());
            var tokensUsed = envelope.path("usage");
            usage.set(
                    new InferenceUsage(
                            "nebius",
                            model,
                            envelope.path("model").asString("unreported"),
                            tokensUsed.path("prompt_tokens").asLong(-1),
                            tokensUsed.path("completion_tokens").asLong(-1),
                            tokensUsed.path("total_tokens").asLong(-1)));
            var choice = envelope.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())
                    || !choice.path("message").path("refusal").asText("").isEmpty())
                throw new PlatformException(
                        502,
                        "INCOMPLETE_MODEL_OUTPUT",
                        "Model output was incomplete or refused. No proposal was accepted.");
            var content = choice.path("message").path("content");
            if (!content.isString()) throw new IllegalArgumentException();
            var result = json.readTree(content.asText());
            if (!result.isObject()) throw new IllegalArgumentException();
            return result;
        } catch (PlatformException expected) {
            throw expected;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PlatformException(
                    409, "MODEL_INTERRUPTED", "Model request interrupted; no change was executed.");
        } catch (java.io.IOException unavailable) {
            throw new PlatformException(
                    503,
                    "MODEL_UNAVAILABLE",
                    "Nebius could not be reached within the bounded response deadline.");
        } catch (RuntimeException malformed) {
            throw new PlatformException(
                    502,
                    "INVALID_MODEL_OUTPUT",
                    "Nebius did not return the required JSON proposal.");
        } finally {
            slots.release();
        }
    }
}
