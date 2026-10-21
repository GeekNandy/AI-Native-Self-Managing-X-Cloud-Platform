package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/**
 * Stateless Streamable HTTP, explicitly pinned to MCP 2025-11-25. No environment apply or patch
 * approval tool.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {
    public static final String VERSION = "2025-11-25";
    private final JsonMapper json;
    private final ControlPlane control;
    private final AgentService agents;
    private final RepositoryWorkspace repositories;
    private final RepairService repairs;
    private final RepairTasks tasks;

    public McpController(
            JsonMapper json,
            ControlPlane control,
            AgentService agents,
            RepositoryWorkspace repositories,
            RepairService repairs,
            RepairTasks tasks) {
        this.json = json;
        this.control = control;
        this.agents = agents;
        this.repositories = repositories;
        this.repairs = repairs;
        this.tasks = tasks;
    }

    public record Id(String id) {}

    public record Prompt(String prompt) {}

    public record Spec(IntentSpec spec) {}

    public record Start(
            String repositoryId,
            String instruction,
            String provider,
            int maxAttempts,
            int timeoutSeconds) {}

    @GetMapping
    ResponseEntity<Void> stream() {
        return ResponseEntity.status(405).header("Allow", "POST").build();
    }

    @DeleteMapping
    ResponseEntity<Void> delete() {
        return ResponseEntity.status(405).header("Allow", "POST").build();
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    ResponseEntity<?> message(
            @RequestBody String body,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String version,
            @RequestHeader(value = "Accept", defaultValue = "") String accept) {
        JsonNode request;
        Object id = null;
        try {
            request = json.readTree(body);
        } catch (RuntimeException malformed) {
            return rpcError(400, null, -32700, "Parse error");
        }
        if (!request.isObject() || !request.path("jsonrpc").asText().equals("2.0"))
            return rpcError(400, null, -32600, "Expected one JSON-RPC 2.0 message");
        if (request.has("id")) {
            var value = request.path("id");
            if (!value.isString() && !value.isIntegralNumber())
                return rpcError(400, null, -32600, "Request id must be a string or integer");
            id = json.convertValue(value, Object.class);
        }
        String method = request.path("method").asText("");
        if (!method.equals("initialize") && version != null && !version.equals(VERSION))
            return rpcError(400, id, -32600, "Supported MCP protocol version is " + VERSION);
        if (!accept.contains("application/json") || !accept.contains("text/event-stream"))
            return rpcError(406, id, -32600, "Accept application/json and text/event-stream");
        if (id == null) return ResponseEntity.accepted().build();
        JsonNode params = request.path("params");
        try {
            Object result =
                    switch (method) {
                        case "initialize" -> {
                            if (!params.isObject()
                                    || !params.path("protocolVersion").isString()
                                    || !params.path("clientInfo").isObject()
                                    || !params.path("capabilities").isObject())
                                throw new IllegalArgumentException();
                            yield Map.of(
                                    "protocolVersion",
                                    VERSION,
                                    "serverInfo",
                                    Map.of("name", "x-cloud-workbench", "version", "0.2.0"),
                                    "capabilities",
                                    Map.of("tools", Map.of("listChanged", false)),
                                    "instructions",
                                    "Tools inspect and propose. Operator approval is available only"
                                            + " through the authenticated workbench API.");
                        }
                        case "ping" -> Map.of();
                        case "tools/list" -> Map.of("tools", toolDefinitions());
                        case "tools/call" -> {
                            if (!params.isObject() || !params.path("name").isString())
                                throw new IllegalArgumentException();
                            JsonNode args =
                                    params.has("arguments")
                                            ? params.path("arguments")
                                            : json.valueToTree(Map.of());
                            if (!args.isObject()) throw new IllegalArgumentException();
                            yield call(params.path("name").asText(), args);
                        }
                        default -> null;
                    };
            if (result == null) return rpcError(200, id, -32601, "Method not found");
            return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "result", result));
        } catch (IllegalArgumentException e) {
            return rpcError(200, id, -32602, "Invalid method or tool parameters");
        } catch (RuntimeException e) {
            return rpcError(200, id, -32603, "Tool service unavailable");
        }
    }

    private Object call(String name, JsonNode args) {
        try {
            Object result =
                    switch (name) {
                        case "list_environments" -> {
                            empty(args);
                            yield Map.of("environments", control.environments());
                        }
                        case "inspect_environment" ->
                                control.environment(read(args, Id.class).id());
                        case "propose_environment" -> agents.run(read(args, Prompt.class).prompt());
                        case "propose_intent" -> control.propose(read(args, Spec.class).spec());
                        case "propose_reconciliation" ->
                                control.proposeReconciliation(read(args, Id.class).id());
                        case "list_repair_tasks" -> {
                            empty(args);
                            yield Map.of("tasks", tasks.examples());
                        }
                        case "inspect_repository" ->
                                repositories.current(read(args, Id.class).id());
                        case "start_repair" -> {
                            var r = read(args, Start.class);
                            yield repairs.submit(
                                    r.repositoryId(),
                                    r.instruction(),
                                    r.provider(),
                                    r.maxAttempts(),
                                    r.timeoutSeconds());
                        }
                        case "inspect_repair" -> repairs.require(read(args, Id.class).id());
                        default -> throw new IllegalArgumentException("Unknown tool");
                    };
            return Map.of(
                    "content",
                    List.of(Map.of("type", "text", "text", json.writeValueAsString(result))),
                    "structuredContent",
                    result,
                    "isError",
                    false);
        } catch (PlatformException expected) {
            return Map.of(
                    "content",
                    List.of(
                            Map.of(
                                    "type",
                                    "text",
                                    "text",
                                    expected.code() + ": " + expected.getMessage())),
                    "isError",
                    true);
        }
    }

    private <T> T read(JsonNode args, Class<T> type) {
        try {
            return json.treeToValue(args, type);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid tool arguments");
        }
    }

    private static void empty(JsonNode args) {
        if (!args.isEmpty()) throw new IllegalArgumentException();
    }

    static List<Map<String, Object>> toolDefinitions() {
        var id = Map.<String, Object>of("id", Map.of("type", "string", "minLength", 1));
        var specProps = new LinkedHashMap<String, Object>();
        for (String name :
                List.of("serviceName", "runtime", "tier", "region", "dataClassification"))
            specProps.put(name, Map.of("type", "string"));
        for (String name :
                List.of(
                        "privateNetwork",
                        "restrictedEgress",
                        "encryptedStorage",
                        "monitoring",
                        "rollback")) specProps.put(name, Map.of("type", "boolean"));
        for (String name : List.of("zones", "replicas"))
            specProps.put(name, Map.of("type", "integer"));
        var spec =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        specProps,
                        "required",
                        new ArrayList<>(specProps.keySet()),
                        "additionalProperties",
                        false);
        return List.of(
                tool("list_environments", "List governed environment observations", Map.of(), true),
                tool(
                        "inspect_environment",
                        "Inspect desired state, observed state and drift",
                        id,
                        true),
                tool(
                        "propose_environment",
                        "Extract and compile a request for operator review",
                        Map.of(
                                "prompt",
                                Map.of("type", "string", "minLength", 1, "maxLength", 4000)),
                        false),
                tool(
                        "propose_intent",
                        "Compile a complete typed intent into an immutable proposal",
                        Map.of("spec", spec),
                        false),
                tool(
                        "propose_reconciliation",
                        "Propose restoration of the approved desired state",
                        id,
                        false),
                tool("list_repair_tasks", "List bounded Java repository examples", Map.of(), true),
                tool("inspect_repository", "Inspect a managed repository head", id, true),
                tool(
                        "start_repair",
                        "Run a bounded source repair and submit a tested patch for review",
                        Map.of(
                                "repositoryId",
                                Map.of("type", "string"),
                                "instruction",
                                Map.of("type", "string", "minLength", 1, "maxLength", 4000),
                                "provider",
                                Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        List.of("mutation-search", "gemini", "nebius")),
                                "maxAttempts",
                                Map.of("type", "integer", "minimum", 1, "maximum", 12),
                                "timeoutSeconds",
                                Map.of("type", "integer", "minimum", 20, "maximum", 120)),
                        false),
                tool(
                        "inspect_repair",
                        "Inspect trace, test results and reviewable patch",
                        id,
                        true));
    }

    private static Map<String, Object> tool(
            String name, String description, Map<String, Object> properties, boolean readOnly) {
        return Map.of(
                "name",
                name,
                "description",
                description,
                "inputSchema",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        new ArrayList<>(properties.keySet()),
                        "additionalProperties",
                        false),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        readOnly,
                        "destructiveHint",
                        false,
                        "openWorldHint",
                        List.of("propose_environment", "start_repair").contains(name)));
    }

    private static ResponseEntity<?> rpcError(int status, Object id, int code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("error", Map.of("code", code, "message", message));
        return ResponseEntity.status(status).body(body);
    }
}
