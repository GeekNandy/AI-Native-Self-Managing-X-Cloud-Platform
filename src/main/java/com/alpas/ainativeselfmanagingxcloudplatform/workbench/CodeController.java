package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/code")
public class CodeController {
    private final RepositoryWorkspace repositories;
    private final RepairService repairs;
    private final RepairTasks tasks;
    @org.springframework.beans.factory.annotation.Autowired private RepairCorpus corpus;
    @org.springframework.beans.factory.annotation.Autowired private RepairBenchmarks benchmarks;
    @org.springframework.beans.factory.annotation.Autowired private PromptPolicies prompts;
    @org.springframework.beans.factory.annotation.Autowired private GitHubPublications publications;
    @org.springframework.beans.factory.annotation.Autowired private GitHubRepositories github;
    @org.springframework.beans.factory.annotation.Autowired private RemoteJobBroker workerJobs;
    @org.springframework.beans.factory.annotation.Autowired private OperationsStatus operations;

    public CodeController(
            RepositoryWorkspace repositories, RepairService repairs, RepairTasks tasks) {
        this.repositories = repositories;
        this.repairs = repairs;
        this.tasks = tasks;
    }

    public record ImportRequest(
            @NotBlank String name,
            @NotNull Map<String, String> files,
            @NotBlank String testClass) {}

    public record CatalogRequest(@NotBlank String taskId) {}

    public record RunRequest(
            @NotBlank String repositoryId,
            @NotBlank @Size(max = 4000) String instruction,
            @NotBlank String provider,
            int maxAttempts,
            int timeoutSeconds) {}

    public record Approval(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String digest) {}

    @GetMapping("/config")
    Map<String, Object> config() {
        var config = new LinkedHashMap<String, Object>(repairs.config());
        config.put("agentPolicy", prompts.active());
        config.put("publication", publications.config());
        config.put("capacity", repairs.capacity());
        config.put("operations", operations.status());
        config.put(
                "benchmark",
                Map.of(
                        "dataset",
                        corpus.dataset().version(),
                        "digest",
                        corpus.digest(),
                        "evaluationTasks",
                        corpus.evaluation().size(),
                        "developmentTasks",
                        corpus.dataset().tasks().size() - corpus.evaluation().size(),
                        "publicSynthetic",
                        true));
        return config;
    }

    @GetMapping("/corpus")
    RepairCorpus.Dataset corpus() {
        return corpus.dataset();
    }

    @GetMapping("/benchmarks")
    List<RepairBenchmarks.Report> benchmarks() {
        return benchmarks.list();
    }

    @PostMapping(value = "/benchmarks", consumes = "application/json")
    RepairBenchmarks.Report benchmark(@RequestBody RepairBenchmarks.Request request) {
        return benchmarks.start(request);
    }

    @PostMapping(value = "/benchmarks/{id}/cancel", consumes = "application/json")
    RepairBenchmarks.Report cancelBenchmark(@PathVariable String id) {
        return benchmarks.cancel(id);
    }

    @PostMapping(value = "/benchmarks/{id}/promote", consumes = "application/json")
    PromptPolicies.Policy promoteBenchmark(@PathVariable String id) {
        return benchmarks.promote(id);
    }

    @GetMapping("/agent-policies")
    Map<String, Object> agentPolicies() {
        return Map.of(
                "active",
                prompts.active(),
                "policies",
                prompts.list(),
                "previous",
                prompts.previousVersions());
    }

    @PostMapping(value = "/agent-policies/candidate", consumes = "application/json")
    PromptPolicies.Policy candidatePolicy() {
        return prompts.derive(repairs.list(), repositories);
    }

    public record Rollback(String target, String expectedActive) {}

    @PostMapping(value = "/agent-policies/rollback", consumes = "application/json")
    PromptPolicies.Policy rollback(@RequestBody Rollback request) {
        return prompts.rollback(request.target(), request.expectedActive());
    }

    @GetMapping("/runs/{id}/publication-preview")
    GitHubPublications.Preview previewPublication(@PathVariable String id) {
        return publications.preview(id);
    }

    @GetMapping("/runs/{id}/publication")
    Object publication(@PathVariable String id) {
        var p = publications.get(id);
        return p == null ? Map.of("status", "NOT_PUBLISHED") : p;
    }

    @PostMapping(value = "/runs/{id}/publish", consumes = "application/json")
    GitHubPublications.Publication publish(
            @PathVariable String id, @Valid @RequestBody Approval approval) {
        return publications.publish(id, approval.digest());
    }

    @GetMapping("/workers/jobs")
    List<Map<String, Object>> workerJobs() {
        return workerJobs.summary();
    }

    @GetMapping("/tasks")
    List<Task> tasks() {
        return tasks.examples();
    }

    @GetMapping("/repositories")
    List<Repository> repositories() {
        return repositories.list();
    }

    @PostMapping(value = "/repositories/github", consumes = "application/json")
    Repository github(@RequestBody GitHubRepositories.Import request) {
        return github.ingest(request);
    }

    @GetMapping("/repositories/{id}/provenance")
    Object provenance(@PathVariable String id) {
        var p = github.provenance(id);
        return p == null ? Map.of("source", "managed-snapshot") : p;
    }

    @GetMapping("/repositories/{id}/files")
    Map<String, String> files(@PathVariable String id) {
        return repositories.files(id);
    }

    @PostMapping(value = "/repositories", consumes = "application/json")
    Repository create(@Valid @RequestBody ImportRequest r) {
        return repositories.create(r.name(), r.files(), r.testClass(), "imported-snapshot");
    }

    @PostMapping(value = "/repositories/catalog", consumes = "application/json")
    Repository create(@Valid @RequestBody CatalogRequest r) {
        var task = tasks.require(r.taskId());
        return repositories.create(task.name(), task.files(), task.testClass(), task.id());
    }

    @GetMapping("/runs")
    List<RepairRun> runs() {
        return repairs.list();
    }

    @GetMapping("/runs/{id}")
    RepairRun run(@PathVariable String id) {
        return repairs.require(id);
    }

    @PostMapping(value = "/runs", consumes = "application/json")
    RepairRun run(@Valid @RequestBody RunRequest r) {
        return repairs.submit(
                r.repositoryId(),
                r.instruction(),
                r.provider(),
                r.maxAttempts(),
                r.timeoutSeconds());
    }

    @GetMapping("/runs/{id}/model-calls")
    List<RepairService.ModelCall> modelCalls(@PathVariable String id) {
        return repairs.modelCalls(id);
    }

    @PostMapping(value = "/runs/{id}/cancel", consumes = "application/json")
    RepairRun cancel(@PathVariable String id) {
        return repairs.cancel(id);
    }

    @PostMapping(value = "/runs/{id}/approve", consumes = "application/json")
    RepairRun approve(@PathVariable String id, @Valid @RequestBody Approval r) {
        return repairs.approve(id, r.digest());
    }

    @GetMapping("/comparisons")
    List<Comparison> comparisons() {
        return repairs.comparisons();
    }

    @PostMapping(value = "/comparisons", consumes = "application/json")
    Comparison compare() {
        return repairs.compare();
    }

    @PostMapping(value = "/comparisons/{id}/promote", consumes = "application/json")
    RepairPolicy promote(@PathVariable String id) {
        return repairs.promote(id);
    }
}
