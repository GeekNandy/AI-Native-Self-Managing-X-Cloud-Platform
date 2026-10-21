package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Durable ordered event journal, retaining the latest 2,000 events. */
@Component
public class AuditLog {
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final ArrayDeque<AuditEvent> events = new ArrayDeque<>();

    private final StateStore store;

    public record Snapshot(List<AuditEvent> events) {}

    public AuditLog(Clock clock) {
        this(clock, StateStore.volatileStore());
    }

    @Autowired
    public AuditLog(Clock clock, StateStore store) {
        this.clock = clock;
        this.store = store;
        store.all("audit", Snapshot.class).forEach(s -> events.addAll(s.events()));
        sequence.set(events.stream().mapToLong(AuditEvent::sequence).max().orElse(0));
    }

    public synchronized void append(
            String type, String actor, String environment, String correlation, String detail) {
        events.addLast(
                new AuditEvent(
                        sequence.incrementAndGet(),
                        clock.instant(),
                        type,
                        actor,
                        environment,
                        correlation,
                        detail));
        if (events.size() > 2_000) events.removeFirst();
        store.put("audit", "journal", new Snapshot(List.copyOf(events)));
    }

    public synchronized List<AuditEvent> list(String environment) {
        return events.stream()
                .filter(e -> environment == null || environment.equals(e.environmentId()))
                .toList();
    }
}
