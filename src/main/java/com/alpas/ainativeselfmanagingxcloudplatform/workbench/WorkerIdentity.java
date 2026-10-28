package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Authenticates only the worker protocol. GitHub claims are checked after RS256 verification. */
@Component
public final class WorkerIdentity {
    private static final String ISSUER = "https://token.actions.githubusercontent.com";
    private final String token, audience, repositoryId, ownerId, workflow;
    private final JsonMapper json;
    private final Clock clock;
    private final Supplier<JsonNode> keySource;
    private Map<String, PublicKey> keys = Map.of();
    private Instant fetched = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkerIdentity(
            @Value("${platform.worker.token:}") String token,
            @Value("${platform.worker.oidc.audience:}") String audience,
            @Value("${platform.worker.oidc.repository-id:}") String repositoryId,
            @Value("${platform.worker.oidc.owner-id:}") String ownerId,
            @Value("${platform.worker.oidc.workflow:}") String workflow,
            JsonMapper json,
            Clock clock) {
        this(token, audience, repositoryId, ownerId, workflow, json, clock, () -> fetchKeys(json));
    }

    WorkerIdentity(
            String token,
            String audience,
            String repositoryId,
            String ownerId,
            String workflow,
            JsonMapper json,
            Clock clock,
            Supplier<JsonNode> keySource) {
        if (!token.isEmpty() && token.length() < 32)
            throw new IllegalArgumentException("Worker token must have at least 32 characters");
        this.token = token;
        this.audience = audience;
        this.repositoryId = repositoryId;
        this.ownerId = ownerId;
        this.workflow = workflow;
        this.json = json;
        this.clock = clock;
        this.keySource = keySource;
    }

    private static JsonNode fetchKeys(JsonMapper json) {
        try {
            var http =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(4))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            var request =
                    HttpRequest.newBuilder(URI.create(ISSUER + "/.well-known/jwks"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();
            var response = BoundedHttp.send(http, request, 65536, Duration.ofSeconds(5));
            if (response.statusCode() != 200)
                throw new IllegalStateException("OIDC key service unavailable");
            return json.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OIDC interrupted");
        } catch (Exception unavailable) {
            throw new IllegalStateException("OIDC keys unavailable");
        }
    }

    private synchronized PublicKey key(String kid) throws Exception {
        var now = clock.instant();
        if ((!keys.containsKey(kid) || fetched.plusSeconds(3600).isBefore(now))
                && fetched.plusSeconds(60).isBefore(now)) {
            fetched = now;
            var replacement = new HashMap<String, PublicKey>();
            for (var k : keySource.get().path("keys")) {
                if (!k.path("kty").asString().equals("RSA")
                        || !k.path("use").asString("sig").equals("sig")
                        || !k.path("alg").asString("RS256").equals("RS256")) continue;
                var modulus =
                        new BigInteger(1, Base64.getUrlDecoder().decode(k.path("n").asString()));
                if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192) continue;
                var exponent =
                        new BigInteger(1, Base64.getUrlDecoder().decode(k.path("e").asString()));
                replacement.put(
                        k.path("kid").asString(),
                        KeyFactory.getInstance("RSA")
                                .generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            }
            keys = Map.copyOf(replacement);
        }
        return keys.get(kid);
    }

    public String authenticate(String header, String instance) {
        if (instance == null) return authenticate(header);
        if (!instance.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) return null;
        var principal = authenticate(header);
        return principal == null ? null : principal + ":" + instance;
    }

    public String authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 16000) return null;
        var value = header.substring(7);
        if (!token.isEmpty()
                && MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        value.getBytes(StandardCharsets.UTF_8))) return "configured-worker";
        if (audience.isBlank() || repositoryId.isBlank() || ownerId.isBlank() || workflow.isBlank())
            return null;
        try {
            var parts = value.split("\\.", -1);
            if (parts.length != 3) return null;
            var h = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!h.path("alg").asString().equals("RS256")
                    || h.has("jku")
                    || h.has("x5u")
                    || h.has("crit")) return null;
            var key = key(h.path("kid").asString());
            if (key == null) return null;
            var verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) return null;
            var c = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long now = clock.instant().getEpochSecond(),
                    iat = c.path("iat").asLong(0),
                    exp = c.path("exp").asLong(0),
                    nbf = c.path("nbf").asLong(0);
            if (!ISSUER.equals(c.path("iss").asString())
                    || !audience.equals(c.path("aud").asString())
                    || !repositoryId.equals(c.path("repository_id").asString())
                    || !ownerId.equals(c.path("repository_owner_id").asString())
                    || !workflow.equals(c.path("workflow_ref").asString())
                    || !workflow.endsWith("@" + c.path("ref").asString())
                    || !Set.of("push", "workflow_dispatch")
                            .contains(c.path("event_name").asString())
                    || !c.path("head_ref").asString("").isEmpty()
                    || iat > now + 30
                    || iat < now - 900
                    || nbf > now + 30
                    || exp <= now
                    || exp - iat > 900
                    || exp <= iat) return null;
            String run = c.path("run_id").asString(), attempt = c.path("run_attempt").asString();
            if (!run.matches("[0-9]{1,20}") || !attempt.matches("[0-9]{1,6}")) return null;
            return "actions:" + run + ":" + attempt;
        } catch (Exception invalid) {
            return null;
        }
    }
}
