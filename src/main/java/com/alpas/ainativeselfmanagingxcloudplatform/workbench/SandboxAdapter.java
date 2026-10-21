package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Durable simulator. It models transitions without provisioning infrastructure. */
@Component
@ConditionalOnProperty(name = "platform.runtime", havingValue = "sandbox", matchIfMissing = true)
public class SandboxAdapter implements RuntimeAdapter {
    private final StateStore store;

    public record Snapshot(String id, IntentSpec intent, boolean unavailable) {}

    public SandboxAdapter() {
        this(StateStore.volatileStore());
    }

    @Autowired
    public SandboxAdapter(StateStore store) {
        this.store = store;
        for (var item : store.all("sandbox", Snapshot.class)) {
            runtime.put(item.id(), item.intent());
            if (item.unavailable()) unavailable.add(item.id());
        }
    }

    private void save(String id) {
        store.put("sandbox", id, new Snapshot(id, runtime.get(id), unavailable.contains(id)));
    }

    @Override
    public String mode() {
        return "durable-simulator";
    }

    private final ConcurrentHashMap<String, IntentSpec> runtime = new ConcurrentHashMap<>();
    private final Set<String> unavailable = ConcurrentHashMap.newKeySet();

    public IntentSpec observe(String environmentId) {
        return unavailable.contains(environmentId) ? null : runtime.get(environmentId);
    }

    public IntentSpec apply(String environmentId, IntentSpec target) {
        runtime.put(environmentId, target);
        unavailable.remove(environmentId);
        save(environmentId);
        return target;
    }

    public void inject(String environmentId, String kind) {
        var current = runtime.get(environmentId);
        if (current == null)
            throw new PlatformException(
                    409, "NOT_PROVISIONED", "Provision the sandbox before injecting a fault.");
        switch (kind) {
            case "replica-loss" -> runtime.put(environmentId, current.withReplicas(1));
            case "egress-open" -> runtime.put(environmentId, current.withRestrictedEgress(false));
            case "observation-lost" -> unavailable.add(environmentId);
            case "observation-restored" -> unavailable.remove(environmentId);
            default ->
                    throw new PlatformException(
                            400,
                            "UNKNOWN_FAULT",
                            "Choose replica-loss, egress-open, observation-lost or"
                                + " observation-restored.");
        }
        save(environmentId);
    }
}
