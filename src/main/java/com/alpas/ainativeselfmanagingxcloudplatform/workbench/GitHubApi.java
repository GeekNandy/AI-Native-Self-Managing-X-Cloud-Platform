package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.time.Duration;

@Component
public final class GitHubApi {
    private final String token;
    private final JsonMapper json;
    private final URI endpoint;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubApi(@Value("${platform.github.token:}") String token, JsonMapper json) {
        this(token, json, URI.create("https://api.github.com"));
    }

    GitHubApi(String token, JsonMapper json, URI endpoint) {
        this.token = token;
        this.json = json;
        this.endpoint = endpoint;
    }

    public boolean canWrite() {
        return !token.isBlank();
    }

    public static String segment(String value) {
        return URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    public JsonNode get(String path) {
        return request("GET", path, null);
    }

    public JsonNode post(String path, Object body) {
        return request("POST", path, body);
    }

    private JsonNode request(String method, String path, Object body) {
        if (!path.startsWith("/repos/") || path.startsWith("//") || path.contains("#"))
            throw new IllegalArgumentException("Invalid GitHub API path");
        try {
            var builder =
                    HttpRequest.newBuilder(endpoint.resolve(path))
                            .timeout(Duration.ofSeconds(12))
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .header("User-Agent", "X-Cloud-Workbench");
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            if (body == null) builder.GET();
            else
                builder.header("Content-Type", "application/json")
                        .method(
                                method,
                                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response =
                    BoundedHttp.send(http, builder.build(), 1_000_000, Duration.ofSeconds(12));
            if (response.statusCode() < 200 || response.statusCode() > 299)
                throw new PlatformException(
                        response.statusCode() == 404 ? 404 : 502,
                        "GITHUB_REQUEST_FAILED",
                        "GitHub rejected the request (HTTP "
                                + response.statusCode()
                                + "). Check access, rate limits and repository coordinates.");
            return json.readTree(response.body());
        } catch (PlatformException e) {
            throw e;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PlatformException(409, "GITHUB_CANCELLED", "GitHub operation cancelled.");
        } catch (Exception failed) {
            throw new PlatformException(
                    502,
                    "GITHUB_UNAVAILABLE",
                    "GitHub response was unavailable, oversized or invalid.");
        }
    }
}
