package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/worker")
public final class WorkerController {
    private final RemoteJobBroker jobs;

    public WorkerController(RemoteJobBroker jobs) {
        this.jobs = jobs;
    }

    public record Hello(String image) {}

    public record Heartbeat(String fence) {}

    private String identity(HttpServletRequest request) {
        var identity = (String) request.getAttribute("workerIdentity");
        if (identity == null)
            throw new PlatformException(401, "WORKER_REQUIRED", "Use a worker credential.");
        return identity;
    }

    @PostMapping(value = "/claim", consumes = "application/json")
    Map<String, Object> claim(HttpServletRequest request, @RequestBody Hello hello) {
        var lease = jobs.claim(identity(request), hello.image());
        return lease == null
                ? Map.of("available", false)
                : Map.of("available", true, "lease", lease);
    }

    @PostMapping(value = "/jobs/{id}/heartbeat", consumes = "application/json")
    Map<String, Object> heartbeat(
            HttpServletRequest request, @PathVariable String id, @RequestBody Heartbeat h) {
        jobs.heartbeat(id, identity(request), h.fence());
        return Map.of("accepted", true);
    }

    @PostMapping(value = "/jobs/{id}/complete", consumes = "application/json")
    Map<String, Object> complete(
            HttpServletRequest request,
            @PathVariable String id,
            @RequestBody RemoteJobBroker.Completion c) {
        jobs.complete(id, identity(request), c);
        return Map.of("accepted", true);
    }
}
