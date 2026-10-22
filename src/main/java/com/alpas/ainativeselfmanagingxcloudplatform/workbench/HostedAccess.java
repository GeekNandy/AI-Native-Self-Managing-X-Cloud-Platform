package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Explicit hosted origin and HTTP Basic access; local bearer operation remains independent. */
@Component
public final class HostedAccess {
    private final URI origin;
    private final byte[] expected;

    public HostedAccess(
            @Value("${platform.web.public-origin:}") String origin,
            @Value("${platform.web.username:}") String username,
            @Value("${platform.web.password:}") String password,
            @Value("${platform.web.auth-required:false}") boolean required) {
        boolean any = !origin.isBlank() || !username.isBlank() || !password.isBlank() || required;
        if (!any) {
            this.origin = null;
            expected = null;
            return;
        }
        if (!username.matches("[A-Za-z0-9._-]{1,64}")
                || password.length() < 16
                || password.length() > 200)
            throw new IllegalArgumentException(
                    "Hosted access requires DEMO_USERNAME and a DEMO_PASSWORD of 16-200"
                        + " characters.");
        try {
            var uri = URI.create(origin);
            boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null
                    || !(uri.getScheme().equals("https") || local && uri.getScheme().equals("http"))
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
                throw new IllegalArgumentException();
            this.origin =
                    new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Set PUBLIC_ORIGIN to the exact HTTPS application origin.");
        }
        expected = hash(username + ":" + password);
    }

    public boolean enabled() {
        return origin != null;
    }

    public boolean allowsHost(String host) {
        return enabled() && origin.getHost().equalsIgnoreCase(host);
    }

    public boolean allowsOrigin(String value) {
        try {
            return enabled() && origin.equals(URI.create(value));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public String role(String header) {
        if (!enabled() || header == null || !header.startsWith("Basic ") || header.length() > 1024)
            return null;
        try {
            byte[] candidate = Base64.getDecoder().decode(header.substring(6));
            return MessageDigest.isEqual(
                            expected, hash(new String(candidate, StandardCharsets.UTF_8)))
                    ? "operator"
                    : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
