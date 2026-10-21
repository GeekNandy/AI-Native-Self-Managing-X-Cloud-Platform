package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkbenchController {
    private final ControlPlane control;
    private final AgentService agents;
    private final IntentInterpreter interpreter;
    private final ScenarioCatalog catalog;
    private final AuditLog audit;
    private final FeedbackStore feedback;
    private final EvaluationService evaluations;

    public WorkbenchController(
            ControlPlane control,
            AgentService agents,
            IntentInterpreter interpreter,
            ScenarioCatalog catalog,
            AuditLog audit,
            FeedbackStore feedback,
            EvaluationService evaluations) {
        this.control = control;
        this.agents = agents;
        this.interpreter = interpreter;
        this.catalog = catalog;
        this.audit = audit;
        this.feedback = feedback;
        this.evaluations = evaluations;
    }

    public record RunRequest(@NotBlank @Size(max = 4000) String prompt) {}

    public record PlanRequest(@NotNull IntentSpec spec) {}

    public record ApplyRequest(
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String digest,
            @NotBlank @Size(min = 8, max = 100) String idempotencyKey) {}

    public record FaultRequest(@NotBlank String kind) {}

    public record EvaluationRequest(@NotBlank String mode) {}

    public record FeedbackRequest(
            @NotBlank String runId,
            @NotBlank @Size(max = 500) String note,
            @NotNull IntentSpec expectedSpec,
            @NotBlank String expectedDecision) {}

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "provider",
                interpreter.provider(),
                "model",
                interpreter.model(),
                "executionMode",
                control.executionMode(),
                "policyVersion",
                PolicyEngine.VERSION,
                "planTtlMinutes",
                15,
                "authentication",
                "scoped-bearer",
                "capabilities",
                List.of(
                        "intent-compilation",
                        "policy-checks",
                        "plan-review",
                        "sandbox-execution",
                        "drift-recovery",
                        "regression-evaluation",
                        "durable-state",
                        "scoped-access",
                        "mcp-tools",
                        "repository-repair"));
    }

    @GetMapping("/session")
    public Map<String, Object> session(jakarta.servlet.http.HttpServletRequest request) {
        return Map.of("role", request.getAttribute("accessRole"));
    }

    @PostMapping(value = "/environments/{id}/refresh", consumes = "application/json")
    public EnvironmentView refresh(@PathVariable String id) {
        return control.refresh(id);
    }

    @GetMapping("/examples")
    public List<Scenario> examples() {
        return catalog.examples();
    }

    @PostMapping(value = "/runs", consumes = "application/json")
    public AgentRun run(@Valid @RequestBody RunRequest request) {
        return agents.run(request.prompt());
    }

    @GetMapping("/runs/{id}")
    public AgentRun run(@PathVariable String id) {
        return agents.requireRun(id);
    }

    @PostMapping(value = "/plans", consumes = "application/json")
    public Plan plan(@Valid @RequestBody PlanRequest request) {
        return control.propose(request.spec());
    }

    @GetMapping("/plans/{id}")
    public Plan plan(@PathVariable String id) {
        return control.plan(id);
    }

    @PostMapping(value = "/plans/{id}/apply", consumes = "application/json")
    public ApplyResult apply(@PathVariable String id, @Valid @RequestBody ApplyRequest request) {
        return control.apply(id, request.digest(), request.idempotencyKey());
    }

    @GetMapping("/environments")
    public List<EnvironmentView> environments() {
        return control.environments();
    }

    @GetMapping("/environments/{id}")
    public EnvironmentView environment(@PathVariable String id) {
        return control.environment(id);
    }

    @PostMapping(value = "/environments/{id}/faults", consumes = "application/json")
    public EnvironmentView fault(
            @PathVariable String id, @Valid @RequestBody FaultRequest request) {
        return control.injectFault(id, request.kind());
    }

    @PostMapping(value = "/environments/{id}/reconcile", consumes = "application/json")
    public Plan reconcile(@PathVariable String id) {
        return control.proposeReconciliation(id);
    }

    @GetMapping("/audit")
    public List<AuditEvent> audit(@RequestParam(required = false) String environmentId) {
        return audit.list(environmentId);
    }

    @GetMapping("/feedback")
    public List<Feedback> feedback() {
        return feedback.list();
    }

    @PostMapping(value = "/feedback", consumes = "application/json")
    public Feedback feedback(@Valid @RequestBody FeedbackRequest request) {
        return feedback.capture(
                request.runId(),
                request.note(),
                request.expectedSpec(),
                request.expectedDecision());
    }

    @GetMapping("/evaluations")
    public List<EvaluationReport> evaluations() {
        return evaluations.history();
    }

    @PostMapping(value = "/evaluations", consumes = "application/json")
    public EvaluationReport evaluate(@Valid @RequestBody EvaluationRequest request) {
        return evaluations.evaluate(request.mode());
    }
}
