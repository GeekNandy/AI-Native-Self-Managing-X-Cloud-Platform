package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

class OperationsReadinessTest {
    @Test
    void unavailableStateStopsReadinessAndUpdatesMetricsWithoutLeakingConfiguration() {
        var state = StateStore.volatileStore();
        var metrics = new SimpleMeterRegistry();
        var controller = new OperationsController(state, null, new SreTelemetry(metrics));
        assertEquals(200, controller.readiness().getStatusCode().value());
        assertEquals(Map.of("status", "READY"), controller.readiness().getBody());
        assertEquals(1.0, metrics.get("platform.readiness.state").gauge().value());
        assertEquals(
                2.0,
                metrics.get("platform.readiness.checks").tag("result", "ready").counter().count());

        state.close();
        var response = controller.readiness();
        assertEquals(503, response.getStatusCode().value());
        assertEquals(Map.of("status", "NOT_READY"), response.getBody());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals(0.0, metrics.get("platform.readiness.state").gauge().value());
        assertEquals(
                1.0,
                metrics.get("platform.readiness.checks").tag("result", "not_ready").counter().count());
    }

    @Test
    void invalidCapacityFailsBeforeAcceptingWork() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCapacity(0, 8, 4, 8, 20));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCapacity(2, 8, 4, 2, 20));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCapacity(2, 8, 4, 8, 8));
    }
}
