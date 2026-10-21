package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Local operator and agent credentials. Agents have proposal authority only. */
@Component
public final class AccessTokens {
    private final String operator;
    private final String agent;
    public AccessTokens(StateStore store, @Value("${platform.auth.operator-token:}") String operator,
                        @Value("${platform.auth.agent-token:}") String agent) {
        this.operator = provision(operator, store.directory(), "operator.token");
        this.agent = provision(agent, store.directory(), "agent.token");
        if (this.operator.equals(this.agent)) throw new IllegalArgumentException("Operator and agent tokens must differ.");
        if (operator.isBlank() && store.directory() != null)
            LoggerFactory.getLogger(AccessTokens.class).info("Operator token: {} (read this local file to unlock the workbench)", store.directory().resolve("operator.token"));
    }
    private static String provision(String configured, Path directory, String name) {
        if (!configured.isBlank()) { validate(configured); return configured; }
        try {
            Path file = directory == null ? null : directory.resolve(name);
            if (file != null && Files.exists(file)) { var token = Files.readString(file).strip(); validate(token); return token; }
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (file != null) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(file, token + "\n");
            }
            return token;
        } catch (Exception e) { throw new IllegalStateException("Cannot provision local access tokens.", e); }
    }
    private static void validate(String token) {
        if (!token.matches("[A-Za-z0-9_-]{24,200}")) throw new IllegalArgumentException("Access tokens need 24-200 URL-safe characters.");
    }
    public String role(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 210) return null;
        byte[] candidate = header.substring(7).getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(candidate, operator.getBytes(StandardCharsets.UTF_8))) return "operator";
        if (MessageDigest.isEqual(candidate, agent.getBytes(StandardCharsets.UTF_8))) return "agent";
        return null;
    }
}
