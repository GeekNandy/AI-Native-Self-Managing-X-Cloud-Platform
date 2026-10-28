package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;

class WorkerIdentityTest {
    final JsonMapper json = new WorkbenchConfiguration().jsonMapper();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC);
    final String workflow = "owner/repo/.github/workflows/repair-worker.yml@refs/heads/feature";

    String encode(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    String jwt(KeyPair pair, Map<String, Object> claims) throws Exception {
        var data =
                encode(json.writeValueAsBytes(Map.of("alg", "RS256", "kid", "test-key")))
                        + "."
                        + encode(json.writeValueAsBytes(claims));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(data.getBytes(StandardCharsets.US_ASCII));
        return "Bearer " + data + "." + encode(signature.sign());
    }

    @Test
    void verifiesSignatureAudienceImmutableRepositoryAndWorkflowClaims() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var pub = (RSAPublicKey) pair.getPublic();
        var keys =
                json.valueToTree(
                        Map.of(
                                "keys",
                                List.of(
                                        Map.of(
                                                "kty",
                                                "RSA",
                                                "kid",
                                                "test-key",
                                                "n",
                                                encode(pub.getModulus().toByteArray()),
                                                "e",
                                                encode(pub.getPublicExponent().toByteArray())))));
        var identity =
                new WorkerIdentity(
                        "",
                        "https://demo.example",
                        "123",
                        "456",
                        workflow,
                        json,
                        clock,
                        () -> keys);
        var claims = new HashMap<String, Object>();
        claims.put("iss", "https://token.actions.githubusercontent.com");
        claims.put("aud", "https://demo.example");
        claims.put("repository_id", "123");
        claims.put("repository_owner_id", "456");
        claims.put("workflow_ref", workflow);
        claims.put("ref", "refs/heads/feature");
        claims.put("event_name", "workflow_dispatch");
        claims.put("run_id", "789");
        claims.put("run_attempt", "1");
        claims.put("iat", clock.instant().getEpochSecond());
        claims.put("nbf", clock.instant().getEpochSecond());
        claims.put("exp", clock.instant().plusSeconds(300).getEpochSecond());
        assertEquals("actions:789:1", identity.authenticate(jwt(pair, claims)));
        var first = identity.authenticate(jwt(pair, claims), "slot-1");
        assertNotEquals(first, identity.authenticate(jwt(pair, claims), "slot-2"));
        claims.put("jti", "refreshed-credential");
        assertEquals(first, identity.authenticate(jwt(pair, claims), "slot-1"));
        assertNull(identity.authenticate(jwt(pair, claims), "slot:invalid"));
        assertNull(identity.authenticate("Bearer invalid", "slot-1"));
        for (var change :
                Map.<String, Object>of(
                                "repository_id",
                                "999",
                                "repository_owner_id",
                                "999",
                                "aud",
                                "other",
                                "event_name",
                                "pull_request",
                                "workflow_ref",
                                "other",
                                "exp",
                                clock.instant().minusSeconds(1).getEpochSecond())
                        .entrySet()) {
            var bad = new HashMap<>(claims);
            bad.put(change.getKey(), change.getValue());
            assertNull(identity.authenticate(jwt(pair, bad)), change.getKey());
        }
        var other = generator.generateKeyPair();
        assertNull(identity.authenticate(jwt(other, claims)));
        assertNull(identity.authenticate("Bearer invalid.token.parts"));
    }
}
