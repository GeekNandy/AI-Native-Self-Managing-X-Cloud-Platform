package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public final class OperationsController {
    private final StateStore state;
    private final OperationsStatus operations;
    private final SreTelemetry telemetry;

    public OperationsController(StateStore state, OperationsStatus operations, SreTelemetry telemetry) {
        this.state = state;
        this.operations = operations;
        this.telemetry = telemetry;
    }

    @GetMapping("/api/operations")
    public Map<String, Object> status() {
        return operations.status();
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readiness() {
        boolean available;
        try {
            state.requireAvailable();
            available = true;
        } catch (RuntimeException unavailable) {
            available = false;
        }
        telemetry.recordReadiness(available);
        return ResponseEntity.status(available ? 200 : 503)
                .header("Cache-Control", "no-store")
                .body(Map.of("status", available ? "READY" : "NOT_READY"));
    }
}
