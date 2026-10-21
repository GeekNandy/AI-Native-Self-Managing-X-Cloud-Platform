package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.*;
import java.util.List;

public interface RuntimeAdapter {
    IntentSpec observe(String environmentId);
    IntentSpec apply(String environmentId, IntentSpec target);
    void inject(String environmentId, String kind);
    default String mode() { return "in-memory-sandbox"; }
    default List<PolicyCheck> capabilityChecks(IntentSpec target) { return List.of(); }
}
