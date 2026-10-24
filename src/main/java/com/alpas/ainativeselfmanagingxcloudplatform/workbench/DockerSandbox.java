package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * No host bind mounts, credentials, repository scripts or network. No host-JVM execution fallback.
 */
@Component
public class DockerSandbox implements SandboxExecutor {
    private final CommandRunner commands;
    private final String image;
    private final String owner;

    public record Owner(String id) {}

    public DockerSandbox(CommandRunner commands, String image) {
        this(commands, image, StateStore.volatileStore());
    }

    @Autowired
    public DockerSandbox(
            CommandRunner commands,
            @Value("${platform.sandbox.image:eclipse-temurin:21-jdk}") String image,
            StateStore store) {
        if (!image.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]{0,200}"))
            throw new IllegalArgumentException("Invalid sandbox image reference");
        this.commands = commands;
        this.image = image;
        var owners = store.all("sandbox-owner", Owner.class);
        owner = owners.isEmpty() ? UUID.randomUUID().toString() : owners.getFirst().id();
        store.put("sandbox-owner", "instance", new Owner(owner));
        // Only this workspace's interrupted containers can be reaped after a process restart.
        try {
            var previous =
                    commands.run(
                            null,
                            Duration.ofSeconds(3),
                            List.of(
                                    "docker",
                                    "ps",
                                    "--all",
                                    "--quiet",
                                    "--filter",
                                    "label=xcloud.sandbox-owner=" + owner));
            if (previous.exitCode() == 0)
                for (String id : previous.output().split("\\s+")) {
                    if (id.matches("[a-f0-9]{12,64}"))
                        commands.run(
                                null,
                                Duration.ofSeconds(3),
                                List.of("docker", "rm", "--force", id));
                }
        } catch (PlatformException unavailable) {
            /* The UI reports unavailable Docker; no host fallback. */
        }
    }

    public Map<String, Object> capabilities() {
        try {
            var check =
                    commands.run(
                            null,
                            Duration.ofSeconds(3),
                            List.of(
                                    "docker",
                                    "image",
                                    "inspect",
                                    image,
                                    "--format",
                                    "{{.Id}}|{{index .Config.Labels \"xcloud.profile\"}}"));
            return Map.of(
                    "available",
                    check.exitCode() == 0,
                    "image",
                    image,
                    "network",
                    "none",
                    "hostMounts",
                    false,
                    "cpu",
                    1,
                    "memoryMiB",
                    384,
                    "pids",
                    96,
                    "profiles",
                    check.output().contains("java21-junit6-v1")
                            ? List.of("java21-main-v1", "java21-junit6-v1")
                            : List.of("java21-main-v1"));
        } catch (PlatformException unavailable) {
            return Map.of(
                    "available",
                    false,
                    "image",
                    image,
                    "detail",
                    "Install Docker and pre-pull the configured Java 21 image. No host execution"
                            + " fallback is permitted.");
        }
    }

    public TestResult test(Map<String, String> files, String testClass, Duration budget) {
        RepositoryWorkspace.validateFiles(files, testClass);
        boolean maven = files.containsKey("pom.xml");
        if (maven
                && !((List<?>) capabilities().getOrDefault("profiles", List.of()))
                        .contains("java21-junit6-v1"))
            throw new PlatformException(
                    503,
                    "MAVEN_RUNNER_REQUIRED",
                    "Build and preload the pinned sandbox/java21 runner image for this Maven"
                            + " profile.");
        long start = System.nanoTime();
        long deadline = start + budget.toNanos();
        String name = "xcloud-test-" + UUID.randomUUID();
        boolean attemptedCreate = false;
        try {
            attemptedCreate = true;
            var create =
                    commands.run(
                            null,
                            phaseBudget(deadline, 15),
                            List.of(
                                    "docker",
                                    "create",
                                    "--pull=never",
                                    "--name",
                                    name,
                                    "--label",
                                    "xcloud.sandbox=true",
                                    "--label",
                                    "xcloud.sandbox-owner=" + owner,
                                    "--network=none",
                                    "--read-only",
                                    "--cap-drop=ALL",
                                    "--security-opt=no-new-privileges",
                                    "--cpus=1",
                                    maven ? "--memory=768m" : "--memory=384m",
                                    maven ? "--memory-swap=768m" : "--memory-swap=384m",
                                    maven ? "--pids-limit=128" : "--pids-limit=96",
                                    "--tmpfs",
                                    "/workspace:rw,nosuid,nodev,noexec,size=4m,mode=755",
                                    "--tmpfs",
                                    maven
                                            ? "/tmp:rw,nosuid,nodev,size=192m,mode=1777"
                                            : "/tmp:rw,nosuid,nodev,size=48m,mode=1777",
                                    "--ulimit",
                                    "nofile=128:128",
                                    "--log-driver=none",
                                    image,
                                    "sleep",
                                    "180"));
            require(create, "container creation");
            require(
                    commands.run(null, phaseBudget(deadline, 10), List.of("docker", "start", name)),
                    "container start");
            // Docker cp does not support all tmpfs mounts. Stream a validated tar via stdin
            // instead.
            require(
                    commands.run(
                            null,
                            phaseBudget(deadline, 10),
                            List.of(
                                    "docker",
                                    "exec",
                                    "-i",
                                    name,
                                    "tar",
                                    "--no-same-owner",
                                    "-xf",
                                    "-",
                                    "-C",
                                    "/workspace"),
                            tar(files)),
                    "source transfer");
            var result =
                    commands.run(
                            null,
                            phaseBudget(deadline, maven ? 40 : 20),
                            List.of(
                                    "docker",
                                    "exec",
                                    "--user",
                                    "10001:10001",
                                    "--workdir",
                                    "/workspace",
                                    name,
                                    "sh",
                                    "-c",
                                    maven
                                            ? "MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=1"
                                                  + " -Djansi.mode=strip -Dstyle.color=never' mvn"
                                                  + " -o -B -ntp -f /opt/runner/pom.xml"
                                                  + " -Dmaven.repo.local=/opt/runner/repository"
                                                  + " -Dtest=\"$1\" test &&"
                                                  + " report=/tmp/build/surefire-reports/TEST-\"$1\".xml"
                                                  + " && test -s \"$report\" && grep -Eq"
                                                  + " 'tests=\"[1-9][0-9]*\"' \"$report\" && grep"
                                                  + " -q 'errors=\"0\"' \"$report\" && grep -q"
                                                  + " 'failures=\"0\"' \"$report\" && grep -q"
                                                  + " 'skipped=\"0\"' \"$report\""
                                            : "mkdir /tmp/classes && find src -name '*.java' -print"
                                                  + " > /tmp/sources && javac -J-Xmx192m -proc:none"
                                                  + " --release 21 -d /tmp/classes @/tmp/sources &&"
                                                  + " java -Xmx192m -XX:ActiveProcessorCount=1 -cp"
                                                  + " /tmp/classes \"$1\"",
                                    "sandbox",
                                    testClass));
            return new TestResult(
                    result.timedOut()
                            ? "TIMEOUT"
                            : result.exitCode() == 0 && !result.truncated() ? "PASS" : "FAIL",
                    result.exitCode(),
                    result.timedOut(),
                    result.truncated(),
                    (System.nanoTime() - start) / 1_000_000,
                    result.output());
        } finally {
            // Cancellation can interrupt create after the daemon accepted it. Clear the thread flag
            // while removing by the preallocated name, then restore it for the worker's terminal
            // state.
            boolean interrupted = Thread.interrupted();
            try {
                if (attemptedCreate)
                    commands.run(
                            null, Duration.ofSeconds(5), List.of("docker", "rm", "--force", name));
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static Duration phaseBudget(long deadline, int maxSeconds) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
            throw new PlatformException(
                    409,
                    "REPAIR_DEADLINE",
                    "The run deadline expired during sandbox initialization.");
        return Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(maxSeconds).toNanos()));
    }

    private static void require(CommandRunner.Result result, String phase) {
        if (result.exitCode() == 0 && !result.timedOut()) return;
        String output = result.output().strip();
        if (output.length() > 2000) output = output.substring(0, 2000);
        throw new PlatformException(
                503,
                result.timedOut() ? "SANDBOX_START_TIMEOUT" : "SANDBOX_START_FAILED",
                "Isolated sandbox "
                        + phase
                        + (result.timedOut()
                                ? " exceeded its startup allowance."
                                : " failed (exit "
                                        + result.exitCode()
                                        + "). Check the Docker daemon and configured image."),
                output.isBlank() ? List.of() : List.of(output));
    }

    /**
     * Minimal ustar encoder for validated, bounded regular files. Owner is root; test UID cannot
     * alter sources.
     */
    static byte[] tar(Map<String, String> files) {
        var out = new ByteArrayOutputStream();
        for (var item : new TreeMap<>(files).entrySet()) {
            byte[] body = item.getValue().getBytes(StandardCharsets.UTF_8), header = new byte[512];
            put(header, 0, 100, item.getKey());
            put(header, 100, 8, "0000644");
            put(header, 108, 8, "0000000");
            put(header, 116, 8, "0000000");
            put(header, 124, 12, String.format("%011o", body.length));
            put(header, 136, 12, "00000000000");
            Arrays.fill(header, 148, 156, (byte) ' ');
            header[156] = '0';
            put(header, 257, 6, "ustar");
            put(header, 263, 2, "00");
            int sum = 0;
            for (byte b : header) sum += b & 255;
            put(header, 148, 8, String.format("%06o", sum) + "\0 ");
            out.writeBytes(header);
            out.writeBytes(body);
            out.writeBytes(new byte[(512 - body.length % 512) % 512]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    private static void put(byte[] target, int offset, int size, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, size));
    }
}
