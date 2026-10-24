package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_TESTS", matches = "true")
class DockerCorpusTest {
    @Test
    void allTwentyFiveBaselinesFailAndReferencePatchesPassInsideTheRealSandbox() throws Exception {
        var json = new WorkbenchConfiguration().jsonMapper();
        var corpus = new RepairCorpus(json);
        var references =
                json.readTree(Files.readString(Path.of("benchmarks/repair-v1-oracles.json")));
        var sandbox = new DockerSandbox(new CommandRunner(), "eclipse-temurin:21-jdk");
        var evidence = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<?>>();
            for (var task : corpus.dataset().tasks())
                futures.add(
                        pool.submit(
                                () -> {
                                    var baseline =
                                            sandbox.test(
                                                    task.files(),
                                                    task.testClass(),
                                                    Duration.ofSeconds(30));
                                    var fixed = new TreeMap<>(task.files());
                                    fixed.put(
                                            "src/main/java/Rules.java",
                                            references.path(task.id()).asString());
                                    var verified =
                                            sandbox.test(
                                                    fixed,
                                                    task.testClass(),
                                                    Duration.ofSeconds(30));
                                    evidence.add(
                                            Map.of(
                                                    "taskId",
                                                    task.id(),
                                                    "split",
                                                    task.split(),
                                                    "baseline",
                                                    baseline,
                                                    "referenceVerification",
                                                    verified,
                                                    "testsDigest",
                                                    RepairCorpus.testDigest(task.files())));
                                    assertEquals(
                                            "FAIL",
                                            baseline.status(),
                                            task.id() + " baseline: " + baseline.output());
                                    assertTrue(
                                            verified.passed(),
                                            task.id() + " reference: " + verified.output());
                                }));
            for (var future : futures) future.get(8, TimeUnit.MINUTES);
        } finally {
            Files.writeString(
                    Path.of("target/docker-corpus-evidence.json"),
                    json.writeValueAsString(
                            Map.of(
                                    "dataset",
                                    corpus.dataset().version(),
                                    "datasetDigest",
                                    corpus.digest(),
                                    "evidenceType",
                                    "corpus-validation-not-model-quality",
                                    "cases",
                                    evidence)));
        }
        assertEquals(25, evidence.size());
    }
}
