package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.List;

class EvaluationProvenanceTest {
    final tools.jackson.databind.json.JsonMapper json = new WorkbenchConfiguration().jsonMapper();

    @Test
    void allLiveProvidersReceiveTheSameCasesAndFixturesRemainSeparate() throws Exception {
        var catalog = new ScenarioCatalog(json);
        var service =
                new EvaluationService(
                        catalog,
                        null,
                        null,
                        null,
                        null,
                        Clock.systemUTC(),
                        null,
                        null,
                        StateStore.volatileStore());
        var expected = service.scenarios("interpreter", "gemini");
        assertEquals(expected, service.scenarios("interpreter", "nebius"));
        assertEquals(expected, service.scenarios("interpreter", "future-provider"));
        assertTrue(expected.size() > service.scenarios("interpreter", "fixture").size());
        assertEquals(
                service.scenarios("control-plane", "fixture"),
                service.scenarios("control-plane", "nebius"));
    }

    @Test
    void historicalReportsMigrateWithoutInventingProviderProvenance() {
        var report =
                new Model.EvaluationReport(
                        "legacy",
                        Clock.systemUTC().instant(),
                        "fixture-interpreter",
                        "a".repeat(64),
                        1,
                        1,
                        100,
                        1,
                        1,
                        0,
                        List.of(),
                        "old report",
                        "fixture",
                        "fixture-v1",
                        "harness-v2");
        var node = (ObjectNode) json.valueToTree(report);
        node.remove("provider");
        node.remove("model");
        node.remove("harnessVersion");
        var store = StateStore.volatileStore();
        store.put("evaluations", "history", java.util.Map.of("reports", List.of(node)));
        var restored =
                store.all("evaluations", EvaluationService.History.class)
                        .getFirst()
                        .reports()
                        .getFirst();
        assertEquals("unrecorded", restored.provider());
        assertEquals("unrecorded", restored.model());
        assertEquals("harness-v1", restored.harnessVersion());
    }
}
