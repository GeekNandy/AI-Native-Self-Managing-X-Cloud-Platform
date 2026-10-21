package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Actual runtime for this demo: an in-memory simulator, never a cloud SDK or shell. */
@Component
public class SandboxAdapter {
    private final ConcurrentHashMap<String, IntentSpec> runtime = new ConcurrentHashMap<>();
    private final Set<String> unavailable = ConcurrentHashMap.newKeySet();

    public IntentSpec observe(String environmentId) {
        return unavailable.contains(environmentId) ? null : runtime.get(environmentId);
    }

    public IntentSpec apply(String environmentId, IntentSpec target) {
        runtime.put(environmentId, target);
        unavailable.remove(environmentId);
        return target;
    }

    public void inject(String environmentId, String kind) {
        var current = runtime.get(environmentId);
        if (current == null) throw new PlatformException(409, "NOT_PROVISIONED", "Provision the sandbox before injecting a fault.");
        switch (kind) {
            case "replica-loss" -> runtime.put(environmentId, current.withReplicas(1));
            case "egress-open" -> runtime.put(environmentId, current.withRestrictedEgress(false));
            case "observation-lost" -> unavailable.add(environmentId);
            case "observation-restored" -> unavailable.remove(environmentId);
            default -> throw new PlatformException(400, "UNKNOWN_FAULT", "Choose replica-loss, egress-open, observation-lost or observation-restored.");
        }
    }
}
