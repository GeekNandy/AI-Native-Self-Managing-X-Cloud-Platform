package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** No host bind mounts, credentials, repository scripts or network. No host-JVM execution fallback. */
@Component
public class DockerSandbox {
    private final CommandRunner commands;
    private final String image;
    public DockerSandbox(CommandRunner commands, @Value("${platform.sandbox.image:eclipse-temurin:21-jdk}") String image) {
        if (!image.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]{0,200}")) throw new IllegalArgumentException("Invalid sandbox image reference");
        this.commands = commands; this.image = image;
    }
    public Map<String,Object> capabilities() {
        try {
            var check = commands.run(null, Duration.ofSeconds(3), List.of("docker", "image", "inspect", image, "--format", "{{.Id}}"));
            return Map.of("available", check.exitCode() == 0, "image", image, "network", "none", "hostMounts", false, "cpu", 1, "memoryMiB", 384, "pids", 96);
        } catch (PlatformException unavailable) { return Map.of("available", false, "image", image, "detail", "Install Docker and pre-pull the configured Java 21 image. No host execution fallback is permitted."); }
    }
    public TestResult test(Map<String,String> files, String testClass, Duration budget) {
        RepositoryWorkspace.validateFiles(files, testClass);
        long start = System.nanoTime();
        String name = "xcloud-test-" + UUID.randomUUID();
        boolean created = false;
        try {
            var create = commands.run(null, Duration.ofSeconds(5), List.of("docker", "create", "--pull=never", "--name", name,
                    "--label", "xcloud.sandbox=true", "--network=none", "--read-only", "--cap-drop=ALL",
                    "--security-opt=no-new-privileges", "--cpus=1", "--memory=384m", "--memory-swap=384m", "--pids-limit=96",
                    "--tmpfs", "/workspace:rw,nosuid,nodev,noexec,size=4m,mode=755", "--tmpfs", "/tmp:rw,nosuid,nodev,size=48m,mode=1777",
                    "--ulimit", "nofile=128:128", "--log-driver=none", image, "sleep", "180"));
            if (create.exitCode() != 0) throw new PlatformException(503, "SANDBOX_UNAVAILABLE", "The Docker sandbox image is unavailable. Pre-pull the configured Java 21 image.");
            created = true;
            require(commands.run(null, Duration.ofSeconds(5), List.of("docker", "start", name)));
            // Docker cp does not support all tmpfs mounts. Stream a validated tar via stdin instead.
            require(commands.run(null, Duration.ofSeconds(5), List.of("docker", "exec", "-i", name, "tar", "--no-same-owner", "-xf", "-", "-C", "/workspace"), tar(files)));
            var result = commands.run(null, Duration.ofMillis(Math.max(1, Math.min(20_000, budget.toMillis() - (System.nanoTime()-start)/1_000_000))),
                    List.of("docker", "exec", "--user", "10001:10001", "--workdir", "/workspace", name, "sh", "-c",
                    "mkdir /tmp/classes && find src -name '*.java' -print > /tmp/sources && javac -J-Xmx192m -proc:none --release 21 -d /tmp/classes @/tmp/sources && java -Xmx192m -XX:ActiveProcessorCount=1 -cp /tmp/classes \"$1\"", "sandbox", testClass));
            return new TestResult(result.timedOut() ? "TIMEOUT" : result.exitCode() == 0 && !result.truncated() ? "PASS" : "FAIL",
                    result.exitCode(), result.timedOut(), result.truncated(), (System.nanoTime()-start)/1_000_000, result.output());
        } finally { if (created) commands.run(null, Duration.ofSeconds(5), List.of("docker", "rm", "--force", name)); }
    }
    private static void require(CommandRunner.Result result) { if (result.exitCode() != 0) throw new PlatformException(503, "SANDBOX_UNAVAILABLE", "Could not initialize the isolated test container."); }
    /** Minimal ustar encoder for validated, bounded regular files. Owner is root; test UID cannot alter sources. */
    static byte[] tar(Map<String,String> files) {
        var out = new ByteArrayOutputStream();
        for (var item : new TreeMap<>(files).entrySet()) {
            byte[] body = item.getValue().getBytes(StandardCharsets.UTF_8), header = new byte[512];
            put(header,0,100,item.getKey()); put(header,100,8,"0000644"); put(header,108,8,"0000000"); put(header,116,8,"0000000");
            put(header,124,12,String.format("%011o",body.length)); put(header,136,12,"00000000000");
            Arrays.fill(header,148,156,(byte)' '); header[156]='0'; put(header,257,6,"ustar"); put(header,263,2,"00");
            int sum=0; for(byte b:header) sum+=b&255; put(header,148,8,String.format("%06o",sum)+"\0 ");
            out.writeBytes(header); out.writeBytes(body); out.writeBytes(new byte[(512-body.length%512)%512]);
        }
        out.writeBytes(new byte[1024]); return out.toByteArray();
    }
    private static void put(byte[] target,int offset,int size,String value) { byte[] bytes=value.getBytes(StandardCharsets.UTF_8); System.arraycopy(bytes,0,target,offset,Math.min(bytes.length,size)); }
}
