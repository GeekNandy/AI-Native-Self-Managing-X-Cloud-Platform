package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
@Primary
public final class ExecutionService implements SandboxExecutor {
    private final SandboxExecutor executor;

    public ExecutionService(
            DockerSandbox local,
            RemoteJobBroker remote,
            @Value("${platform.execution.mode:local}") String mode) {
        executor =
                switch (mode) {
                    case "local" -> local;
                    case "remote" -> remote;
                    default ->
                            throw new IllegalArgumentException(
                                    "Execution mode must be local or remote");
                };
    }

    public Map<String, Object> capabilities() {
        return executor.capabilities();
    }

    public TestResult test(Map<String, String> files, String testClass, Duration budget) {
        return executor.test(files, testClass, budget);
    }

    public TestResult test(
            Map<String, String> files,
            String testClass,
            Duration budget,
            String owner,
            String phase) {
        return executor.test(files, testClass, budget, owner, phase);
    }
}
