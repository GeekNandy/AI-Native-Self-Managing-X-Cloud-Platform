package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import java.time.Duration;
import java.util.*;

/**
 * Provider-neutral proposal boundary. Implementations expose no execution or approval capability.
 */
public interface PatchPlanner {
    boolean supports(String provider);

    boolean configured(String provider);

    List<String> providers();

    String model(String provider);

    InferenceUsage usage();

    List<Change> propose(
            String provider,
            String instruction,
            Map<String, String> files,
            TestResult tests,
            List<Step> previous,
            Duration budget);
}
