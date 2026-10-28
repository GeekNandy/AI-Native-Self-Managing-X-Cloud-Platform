package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public final class OperationsController {
    private final StateStore state;
    private final OperationsStatus operations;

    public OperationsController(StateStore state, OperationsStatus operations) {
        this.state = state;
        this.operations = operations;
    }

    @GetMapping("/api/operations")
    public Map<String, Object> status() {
        return operations.status();
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readiness() {
        try {
            state.requireAvailable();
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .body(Map.of("status", "READY"));
        } catch (RuntimeException unavailable) {
            return ResponseEntity.status(503).header("Cache-Control", "no-store")
                    .body(Map.of("status", "NOT_READY"));
        }
    }
}
