package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Human-reviewed corrections become additional evaluation cases, never authority or policy edits. */
@Service
public class FeedbackStore {
    private final AgentService agents;
    private final Clock clock;
    private final AuditLog audit;
    private final ArrayList<Feedback> feedback = new ArrayList<>();

    public FeedbackStore(AgentService agents, Clock clock, AuditLog audit) {
        this.agents = agents; this.clock = clock; this.audit = audit;
    }

    public synchronized Feedback capture(String runId, String note, IntentSpec expectedSpec, String expectedDecision) {
        agents.requireRun(runId);
        if (feedback.size() >= 100) throw new PlatformException(429, "FEEDBACK_CAPACITY", "The local feedback limit has been reached.");
        if (note == null || note.isBlank() || note.length() > 500 || expectedSpec == null
                || !List.of("ALLOW", "DENY", "INVALID").contains(String.valueOf(expectedDecision)))
            throw new PlatformException(400, "INVALID_FEEDBACK", "Provide a note, a complete expected intent, and ALLOW, DENY or INVALID.");
        var item = new Feedback(UUID.randomUUID().toString(), runId, note, expectedSpec, expectedDecision, clock.instant());
        feedback.add(item);
        audit.append("FEEDBACK_CAPTURED", "local-operator", expectedSpec.environmentId(), runId, "Added a regression case; policy and interpreter unchanged.");
        return item;
    }

    public synchronized List<Feedback> list() { return List.copyOf(feedback); }
}
