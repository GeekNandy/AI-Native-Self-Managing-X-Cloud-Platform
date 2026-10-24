package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

@Component
public final class RepairCorpus {
    public record Case(
            String id,
            String split,
            String category,
            String instruction,
            Map<String, String> files,
            String testClass) {
        public Task task() {
            return new Task(id, id, instruction, files, testClass);
        }
    }

    public record Dataset(String version, String harness, List<Case> tasks) {}

    private final Dataset dataset;
    private final String digest;

    public RepairCorpus(JsonMapper json) throws java.io.IOException {
        try (var stream = new ClassPathResource("evaluation/repair-v1.json").getInputStream()) {
            dataset = json.readValue(stream, Dataset.class);
        }
        if (dataset.tasks().size() != 25
                || dataset.tasks().stream().map(Case::id).distinct().count() != 25
                || evaluation().size() != 20)
            throw new IllegalStateException("Invalid repair corpus");
        dataset.tasks().forEach(c -> RepositoryWorkspace.validateFiles(c.files(), c.testClass()));
        digest = IntentCompiler.digest(json.writeValueAsString(dataset));
    }

    public Dataset dataset() {
        return dataset;
    }

    public String digest() {
        return digest;
    }

    public List<Case> evaluation() {
        return dataset.tasks().stream().filter(c -> c.split().equals("evaluation")).toList();
    }

    public static String testDigest(Map<String, String> files) {
        var tests = new TreeMap<String, String>();
        files.forEach(
                (p, c) -> {
                    if (p.startsWith("src/test/")) tests.put(p, c);
                });
        return IntentCompiler.digest(
                new WorkbenchConfiguration().jsonMapper().writeValueAsString(tests));
    }

    public boolean evaluationInputs(Map<String, String> files) {
        var hash = testDigest(files);
        return evaluation().stream().anyMatch(c -> testDigest(c.files()).equals(hash));
    }
}
