package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Process-local, ordered event log. Retains the latest 2,000 events; not durable audit storage. */
@Component
public class AuditLog {
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final ArrayDeque<AuditEvent> events = new ArrayDeque<>();

    public AuditLog(Clock clock) { this.clock = clock; }

    public synchronized void append(String type, String actor, String environment, String correlation, String detail) {
        events.addLast(new AuditEvent(sequence.incrementAndGet(), clock.instant(), type, actor, environment, correlation, detail));
        if (events.size() > 2_000) events.removeFirst();
    }

    public synchronized List<AuditEvent> list(String environment) {
        return events.stream().filter(e -> environment == null || environment.equals(e.environmentId())).toList();
    }
}
