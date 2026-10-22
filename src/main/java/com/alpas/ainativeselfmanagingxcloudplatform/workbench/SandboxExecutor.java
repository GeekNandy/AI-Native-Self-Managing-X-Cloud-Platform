package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import java.time.Duration;
import java.util.Map;

/** Execution authority is held by a constrained runner, independently of model proposals. */
public interface SandboxExecutor {
    Map<String, Object> capabilities();

    CodeModel.TestResult test(Map<String, String> files, String testClass, Duration budget);

    default CodeModel.TestResult test(
            Map<String, String> files,
            String testClass,
            Duration budget,
            String owner,
            String phase) {
        return test(files, testClass, budget);
    }
}
