package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

class DockerSandboxTest {
    @Test
    void stalledContainerCreationHasABoundedStartupErrorAndStillAttemptsCleanup() {
        var calls = new java.util.ArrayList<List<String>>();
        var runner =
                new CommandRunner() {
                    @Override
                    public Result run(
                            Path cwd, Duration timeout, List<String> command, byte[] input) {
                        calls.add(command);
                        if (command.size() > 1 && command.get(1).equals("create")) {
                            assertTrue(timeout.toSeconds() <= 15);
                            return new Result(-1, "daemon creation stalled", true, false, 15000);
                        }
                        return new Result(0, "", false, false, 1);
                    }
                };
        var task = new RepairTasks().examples().getFirst();
        var sandbox = new DockerSandbox(runner, "eclipse-temurin:21-jdk");
        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> sandbox.test(task.files(), task.testClass(), Duration.ofSeconds(90)));
        assertEquals("SANDBOX_START_TIMEOUT", failure.code());
        assertTrue(failure.details().contains("daemon creation stalled"));
        assertTrue(
                calls.stream()
                        .anyMatch(
                                c ->
                                        c.size() > 2
                                                && c.get(1).equals("rm")
                                                && c.get(2).equals("--force")));
    }
}
