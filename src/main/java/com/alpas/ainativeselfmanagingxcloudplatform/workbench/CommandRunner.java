package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Fixed-argument process runner. No shell interpolation; bounded time and captured output. */
@Component
public class CommandRunner {
    private final boolean diagnostics;

    public CommandRunner() {
        this(false);
    }

    private CommandRunner(boolean diagnostics) {
        this.diagnostics = diagnostics;
    }

    static CommandRunner forDiagnostics() {
        return new CommandRunner(true);
    }

    private List<String> commandLine(Path cwd, List<String> command) {
        if (command == null || command.isEmpty()) throw denied();
        // Select the executable from literals. API values can only occupy subsequent argument
        // positions; no endpoint or model output can choose a host executable or shell.
        var selected = new ArrayList<String>();
        switch (command.getFirst()) {
            case "git" -> selected.add("git");
            case "docker" -> selected.add("docker");
            case "sleep" -> {
                if (!diagnostics) throw denied();
                selected.add("sleep");
            }
            case "env" -> {
                if (!diagnostics) throw denied();
                selected.add("env");
            }
            case "tar" -> {
                if (!diagnostics) throw denied();
                selected.add("tar");
            }
            default -> throw denied();
        }
        if (cwd != null) {
            if (!command.getFirst().equals("git")) throw denied();
            // Git's explicit directory option keeps working-directory values out of process
            // executable resolution. RepositoryWorkspace supplies only retained managed identities.
            selected.add("-C");
            selected.add(cwd.toAbsolutePath().normalize().toString());
        }
        selected.addAll(command.subList(1, command.size()));
        return selected;
    }

    private static PlatformException denied() {
        return new PlatformException(
                400, "TOOL_DENIED", "The host tool is not in the fixed execution allowlist.");
    }

    public record Result(
            int exitCode, String output, boolean timedOut, boolean truncated, long durationMs) {}

    public Result run(Path cwd, Duration timeout, List<String> command) {
        return run(cwd, timeout, command, new byte[0]);
    }

    public Result run(Path cwd, Duration timeout, List<String> command, byte[] input) {
        List<String> selected = commandLine(cwd, command);
        long start = System.nanoTime();
        Process process = null;
        var io = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var builder = new ProcessBuilder(selected).redirectErrorStream(true);
            // Children receive no API keys, bearer credentials, SSH agent, proxy credentials or
            // cloud environment.
            builder.environment().clear();
            builder.environment()
                    .put(
                            "PATH",
                            System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
            builder.environment().put("LANG", "C.UTF-8");
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            final Process child = process;
            var output =
                    io.submit(
                            () -> {
                                var kept = new java.io.ByteArrayOutputStream();
                                boolean truncated = false;
                                try (var stream = child.getInputStream()) {
                                    byte[] buffer = new byte[4096];
                                    int count;
                                    while ((count = stream.read(buffer)) != -1) {
                                        int remaining = 65_536 - kept.size();
                                        if (count > remaining) truncated = true;
                                        if (remaining > 0)
                                            kept.write(buffer, 0, Math.min(count, remaining));
                                    }
                                }
                                return new Object[] {
                                    kept.toString(StandardCharsets.UTF_8), truncated
                                };
                            });
            io.submit(
                    () -> {
                        try (var stdin = child.getOutputStream()) {
                            stdin.write(input);
                        } catch (Exception ignored) {
                        }
                    });
            boolean complete =
                    process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!complete) stop(process);
            var captured = output.get(3, TimeUnit.SECONDS);
            return new Result(
                    complete ? process.exitValue() : -1,
                    (String) captured[0],
                    !complete,
                    (boolean) captured[1],
                    (System.nanoTime() - start) / 1_000_000);
        } catch (InterruptedException interrupted) {
            if (process != null) stop(process);
            Thread.currentThread().interrupt();
            throw new PlatformException(
                    409, "RUN_CANCELLED", "The bounded operation was cancelled.");
        } catch (Exception failure) {
            if (process != null) stop(process);
            throw new PlatformException(
                    503,
                    "TOOL_UNAVAILABLE",
                    "A required local tool could not complete. Check Git, Docker and the configured"
                            + " image.");
        } finally {
            // Executor.close waits for blocked stream readers. Kill the process in the catch path
            // first, then interrupt readers without delaying cancellation on executor termination.
            io.shutdownNow();
        }
    }

    private static void stop(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    public String require(Path cwd, String... args) {
        var result = run(cwd, Duration.ofSeconds(10), List.of(args));
        if (result.exitCode() != 0 || result.truncated())
            throw new PlatformException(
                    409,
                    "REPOSITORY_CONFLICT",
                    "The repository operation failed; refresh the repository and review its"
                            + " state.");
        return result.output().strip();
    }
}
