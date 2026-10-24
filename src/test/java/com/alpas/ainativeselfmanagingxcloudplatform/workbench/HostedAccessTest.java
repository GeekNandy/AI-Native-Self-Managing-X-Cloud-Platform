package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

class HostedAccessTest {
    private final HostedAccess access =
            new HostedAccess(
                    "https://demo.example.com", "reviewer", "a-long-random-test-password", true);

    private String basic(String password) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString(("reviewer:" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void configurationFailsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HostedAccess("https://demo.example.com", "", "", true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new HostedAccess(
                                "http://demo.example.com",
                                "reviewer",
                                "a-long-random-test-password",
                                true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new HostedAccess(
                                "https://demo.example.com/path",
                                "reviewer",
                                "a-long-random-test-password",
                                true));
        assertFalse(new HostedAccess("", "", "", false).enabled());
    }

    @Test
    void exactCredentialsAndOriginAreRequired() {
        assertEquals("operator", access.role(basic("a-long-random-test-password")));
        assertNull(access.role(basic("wrong-password")));
        assertNull(access.role("Basic bad%"));
        assertNull(access.role(null));
        assertTrue(access.allowsHost("demo.example.com"));
        assertFalse(access.allowsHost("demo.example.com.attacker.test"));
        assertTrue(access.allowsOrigin("https://demo.example.com"));
        assertFalse(access.allowsOrigin("https://attacker.test"));
    }

    @Test
    void gateCoversUiApiAndMetricsWhileHealthAndScopedAgentStillWork() throws Exception {
        var tokens =
                new AccessTokens(
                        StateStore.volatileStore(),
                        "test-operator-token-0123456789",
                        "test-agent-token-0123456789");
        var filter = new LocalBoundaryFilter(tokens, access);
        for (String path :
                new String[] {
                    "/", "/app.js", "/api/config", "/api/environments", "/actuator/metrics", "/mcp"
                }) {
            var response = invoke(filter, path, null, null);
            assertEquals(401, response.getStatus(), path);
            assertTrue(response.getHeader("WWW-Authenticate").startsWith("Basic"));
        }
        assertEquals(200, invoke(filter, "/actuator/health", null, null).getStatus());
        assertEquals(
                200,
                invoke(
                                filter,
                                "/api/environments",
                                basic("a-long-random-test-password"),
                                "https://demo.example.com")
                        .getStatus());
        assertEquals(
                403,
                invoke(
                                filter,
                                "/api/environments",
                                basic("a-long-random-test-password"),
                                "https://attacker.test")
                        .getStatus());
        assertEquals(
                200,
                invoke(filter, "/api/environments", "Bearer test-agent-token-0123456789", null)
                        .getStatus());
    }

    @Test
    void streamedBodiesCannotBypassTheOneMebibyteLimit() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/runs");
        request.setContent(new byte[1_048_577]);
        var bounded = new BoundedRequest(request);
        assertThrows(java.io.IOException.class, () -> bounded.getInputStream().readAllBytes());
    }

    private MockHttpServletResponse invoke(
            LocalBoundaryFilter filter, String path, String authorization, String origin)
            throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setServerName("demo.example.com");
        request.setServerPort(10000);
        request.setScheme("http");
        if (authorization != null) request.addHeader("Authorization", authorization);
        if (origin != null) request.addHeader("Origin", origin);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response;
    }
}
