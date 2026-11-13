package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Low-cardinality reliability signals. A readiness probe is an observation, not an HA claim. */
@Component
public final class SreTelemetry {
    private final AtomicInteger readiness = new AtomicInteger(0);
    private final Counter readyChecks;
    private final Counter notReadyChecks;

    @org.springframework.beans.factory.annotation.Autowired
    public SreTelemetry(MeterRegistry metrics, StateStore state) {
        this(metrics);
        Gauge.builder("platform.coordinator.epoch", state, StateStore::leaderEpoch)
                .description("Persisted epoch of this coordinator instance")
                .register(metrics);
        Gauge.builder("platform.coordinator.fenced", state,
                s -> Boolean.TRUE.equals(s.status().get("available")) ? 0 : 1)
                .description("Whether this coordinator has permanently failed closed")
                .register(metrics);
    }

    public SreTelemetry(MeterRegistry metrics) {
        Gauge.builder("platform.readiness.state", readiness, AtomicInteger::doubleValue)
                .description("Last observed /readyz result: 1 ready, 0 not ready or not yet checked")
                .register(metrics);
        readyChecks = Counter.builder("platform.readiness.checks")
                .tag("result", "ready")
                .description("Readiness probes classified by outcome")
                .register(metrics);
        notReadyChecks = Counter.builder("platform.readiness.checks")
                .tag("result", "not_ready")
                .description("Readiness probes classified by outcome")
                .register(metrics);
    }

    public void recordReadiness(boolean available) {
        readiness.set(available ? 1 : 0);
        (available ? readyChecks : notReadyChecks).increment();
    }
}
