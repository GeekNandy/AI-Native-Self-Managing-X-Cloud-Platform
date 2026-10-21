package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InterpreterTest {
    JsonMapper json = new WorkbenchConfiguration().jsonMapper();
    ConfiguredInterpreter interpreter;

    @BeforeEach void setup() throws Exception {
        interpreter = new ConfiguredInterpreter("fixture", "", "", new ScenarioCatalog(json), json, HttpClient.newHttpClient());
    }
    String response(Object intent, String reason) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("intent", intent); envelope.put("questions", List.of());
        return json.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", reason,
                "content", Map.of("parts", List.of(Map.of("text", json.writeValueAsString(envelope))))))));
    }
    void rejected(String code, Runnable action) { assertEquals(code, assertThrows(PlatformException.class, action::run).code()); }

    @Test void acceptsCompleteStructuredResponseButStillRequiresIndependentPolicy() {
        var unsafe = EvaluationService.safe().withRestrictedEgress(false);
        var extracted = interpreter.parseResponse(response(unsafe, "STOP"));
        assertEquals(unsafe, extracted);
        assertFalse(new IntentCompiler(new PolicyEngine(), json).analyze(extracted).allowed());
    }

    @Test void rejectsUnknownAuthorityFieldsAndMissingFields() {
        var node = json.valueToTree(EvaluationService.safe()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) node).put("approved", true);
        rejected("INVALID_MODEL_OUTPUT", () -> interpreter.parseResponse(response(node, "STOP")));
        ((tools.jackson.databind.node.ObjectNode) node).remove("approved");
        ((tools.jackson.databind.node.ObjectNode) node).remove("privateNetwork");
        rejected("INVALID_MODEL_OUTPUT", () -> interpreter.parseResponse(response(node, "STOP")));
    }

    @Test void rejectsNullBooleansAndNumericCoercions() {
        var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(EvaluationService.safe());
        node.putNull("privateNetwork");
        rejected("INVALID_MODEL_OUTPUT", () -> interpreter.parseResponse(response(node, "STOP")));
        node.put("privateNetwork", true); node.put("replicas", 3.5);
        rejected("INVALID_MODEL_OUTPUT", () -> interpreter.parseResponse(response(node, "STOP")));
        node.put("replicas", "3");
        rejected("INVALID_MODEL_OUTPUT", () -> interpreter.parseResponse(response(node, "STOP")));
    }

    @Test void truncationAndMissingRequirementsCannotCreatePlans() {
        rejected("INCOMPLETE_MODEL_OUTPUT", () -> interpreter.parseResponse(response(EvaluationService.safe(), "MAX_TOKENS")));
        rejected("CLARIFICATION_REQUIRED", () -> interpreter.parseResponse(response(null, "STOP")));
        rejected("INVALID_MODEL_OUTPUT", () -> interpreter.parseResponse("not-json"));
    }

    @Test void unknownOfflineRequestsAskForClarificationInsteadOfFabricatingInterpretation() {
        rejected("CLARIFICATION_REQUIRED", () -> interpreter.interpret("Invent an environment from this incomplete prompt"));
    }
}
