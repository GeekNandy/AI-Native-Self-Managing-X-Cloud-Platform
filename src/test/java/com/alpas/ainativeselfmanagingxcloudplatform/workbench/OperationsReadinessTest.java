package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class OperationsReadinessTest {
    @Test
    void unavailableStateStopsReadinessWithoutLeakingConfiguration() {
        var state = StateStore.volatileStore();
        var controller = new OperationsController(state, null);
        assertEquals(200, controller.readiness().getStatusCode().value());
        assertEquals(Map.of("status", "READY"), controller.readiness().getBody());
        state.close();
        var response = controller.readiness();
        assertEquals(503, response.getStatusCode().value());
        assertEquals(Map.of("status", "NOT_READY"), response.getBody());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
    }

    @Test
    void invalidCapacityFailsBeforeAcceptingWork() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCapacity(0, 8, 4, 8, 20));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCapacity(2, 8, 4, 2, 20));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCapacity(2, 8, 4, 8, 8));
    }
}
