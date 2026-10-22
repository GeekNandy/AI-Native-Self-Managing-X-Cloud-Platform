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
    @org.springframework.beans.factory.annotation.Autowired private GitHubRepositories github;
    @org.springframework.beans.factory.annotation.Autowired private RemoteJobBroker workerJobs;

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
        return repairs.config();
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
