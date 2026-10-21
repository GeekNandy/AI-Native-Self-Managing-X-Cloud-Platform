package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class ScenarioCatalog {
    private final List<Scenario> scenarios;

    public ScenarioCatalog(JsonMapper json) throws IOException {
        try (var stream = new ClassPathResource("evaluation/scenarios.json").getInputStream()) {
            scenarios = List.copyOf(Arrays.asList(json.readValue(stream, Scenario[].class)));
        }
    }

    public List<Scenario> all() {
        return scenarios;
    }

    public List<Scenario> examples() {
        return scenarios.stream().filter(Scenario::fixture).toList();
    }
}
