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
