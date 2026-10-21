package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class AgentService {
    private final IntentInterpreter interpreter;
    private final ControlPlane control;
    private final Clock clock;
    private final AuditLog audit;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Semaphore retentionSlots = new Semaphore(500);

    public AgentService(IntentInterpreter interpreter, ControlPlane control, Clock clock, AuditLog audit, MeterRegistry metrics) {
        this.interpreter = interpreter; this.control = control; this.clock = clock; this.audit = audit; this.metrics = metrics;
    }

    public AgentRun run(String prompt) {
        if (!retentionSlots.tryAcquire()) throw new PlatformException(429, "RUN_CAPACITY", "The local workbench reached its run retention limit. Restart to clear it.");
        try { return captureRun(prompt);
        } catch (RuntimeException failure) { retentionSlots.release(); throw failure; }
    }

    private AgentRun captureRun(String prompt) {
        String id = UUID.randomUUID().toString();
        long started = System.nanoTime();
        Plan plan = null;
        String status;
        var messages = new ArrayList<String>();
        double interpretationMs;
        try {
            var spec = interpreter.interpret(prompt);
            interpretationMs = (System.nanoTime() - started) / 1_000_000.0;
            plan = control.propose(spec);
            status = plan.policy().allowed() ? "AWAITING_APPROVAL" : "BLOCKED";
            messages.add(plan.policy().allowed() ? "Candidate intent compiled. Review the plan before sandbox execution."
                    : "The independent policy engine denied this candidate. Execution is unavailable.");
        } catch (PlatformException failure) {
            if (!List.of("CLARIFICATION_REQUIRED", "INVALID_INTENT").contains(failure.code())) throw failure;
            interpretationMs = (System.nanoTime() - started) / 1_000_000.0;
            status = failure.code();
            messages.add(failure.getMessage());
            messages.addAll(failure.details());
        }
        var result = new AgentRun(id, clock.instant(), prompt, interpreter.provider(), status, interpretationMs, plan, messages);
        runs.put(id, result);
        metrics.timer("platform.agent.run", "provider", interpreter.provider(), "status", status)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        audit.append("AGENT_RUN_COMPLETED", "bounded-interpreter", plan == null ? null : plan.environmentId(), id, status);
        return result;
    }

    public AgentRun requireRun(String id) {
        var run = runs.get(id);
        if (run == null) throw new PlatformException(404, "RUN_NOT_FOUND", "Agent run not found.");
        return run;
    }
}
