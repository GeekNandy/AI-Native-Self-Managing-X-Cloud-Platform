package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/** Safe configuration diagnostics. Configured capabilities are not live verification receipts. */
@Service
public final class OperationsStatus {
    private final StateStore state;
    private final RepairService repairs;
    private final PatchPlanner models;
    private final GitHubPublications publications;
    private final boolean ephemeral;

    public OperationsStatus(StateStore state, RepairService repairs, PatchPlanner models,
            GitHubPublications publications, @Value("${platform.web.ephemeral:false}") boolean ephemeral) {
        this.state = state;
        this.repairs = repairs;
        this.models = models;
        this.publications = publications;
        this.ephemeral = ephemeral;
    }

    public Map<String, Object> status() {
        var storage = new LinkedHashMap<>(state.status());
        boolean durable = storage.get("backend").equals("postgresql") && !ephemeral;
        storage.put("survivesDeploysConfigured", durable);
        var providers = models.providers().stream().filter(models::supports)
                .map(provider -> Map.<String, Object>of(
                        "provider", provider, "model", models.model(provider),
                        "configured", models.configured(provider))).toList();
        var pending = new ArrayList<String>();
        if (providers.stream().noneMatch(p -> Boolean.TRUE.equals(p.get("configured"))))
            pending.add("Configure a live model and collect a real repair receipt.");
        if (!durable) pending.add("Connect PostgreSQL and verify recovery across a hosted restart.");
        var publication = publications.config();
        if (!Boolean.TRUE.equals(publication.get("enabled")))
            pending.add("Configure a scoped publication credential and verify one reviewed draft PR.");
        return Map.of("state", storage, "models", providers, "publication", publication,
                "capacity", repairs.capacity(), "pendingActivation", pending,
                "topology", "One fenced coordinator; execution workers scale independently",
                "evidence", "Configuration only; live validation requires separate receipts");
    }
}
