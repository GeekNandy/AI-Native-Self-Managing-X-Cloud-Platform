package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * Enforces a local origin plus scoped bearer credentials. No cookie or query-string credentials.
 */
@Component
public class LocalBoundaryFilter extends OncePerRequestFilter {
    private final AccessTokens tokens;
    private final HostedAccess hosted;
    @org.springframework.beans.factory.annotation.Autowired private StateStore state;
    @org.springframework.beans.factory.annotation.Autowired private WorkerIdentity workers;

    public LocalBoundaryFilter(AccessTokens tokens, HostedAccess hosted) {
        this.tokens = tokens;
        this.hosted = hosted;
    }

    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                    + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action"
                    + " 'self'");
        boolean permitted =
                LOCAL.contains(request.getServerName())
                        || hosted.allowsHost(request.getServerName());
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                var source = URI.create(origin);
                int port =
                        source.getPort() == -1
                                ? ("https".equals(source.getScheme()) ? 443 : 80)
                                : source.getPort();
                permitted &=
                        hosted.enabled()
                                ? hosted.allowsOrigin(origin)
                                : LOCAL.contains(source.getHost())
                                        && source.getHost().equals(request.getServerName())
                                        && source.getScheme().equals(request.getScheme())
                                        && port == request.getServerPort();
            } catch (RuntimeException malformed) {
                permitted = false;
            }
        }
        if (!permitted) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"code\":\"LOCAL_ORIGIN_REQUIRED\",\"message\":\"Use the local"
                                    + " workbench origin.\",\"details\":[]}");
            return;
        }
        String path = request.getRequestURI();
        if (state != null && !path.equals("/actuator/health")) {
            try {
                state.requireAvailable();
            } catch (PlatformException unavailable) {
                deny(
                        response,
                        503,
                        "STATE_UNAVAILABLE",
                        "This coordinator has been replaced or its state is unavailable.");
                return;
            }
        }
        String header = request.getHeader("Authorization");
        if (path.startsWith("/api/worker/")) {
            String worker = workers == null ? null : workers.authenticate(header);
            if (worker == null) {
                deny(response, 401, "WORKER_REQUIRED", "Use a scoped worker credential.");
                return;
            }
            if (!request.getMethod().equals("POST")) {
                deny(response, 405, "METHOD_NOT_ALLOWED", "Worker protocol accepts POST only.");
                return;
            }
            if (request.getContentLengthLong() > 1_048_576) {
                deny(response, 413, "REQUEST_TOO_LARGE", "Requests are limited to 1 MiB.");
                return;
            }
            request.setAttribute("workerIdentity", worker);
            response.setHeader("Cache-Control", "no-store");
            chain.doFilter(request, response);
            return;
        }
        String accessRole = tokens.role(header);
        if (accessRole == null) accessRole = hosted.role(header);
        if (hosted.enabled() && !path.equals("/actuator/health") && accessRole == null) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"X Cloud\", charset=\"UTF-8\"");
            response.setHeader("Cache-Control", "no-store");
            deny(response, 401, "AUTH_REQUIRED", "Enter the workspace username and password.");
            return;
        }
        if (hosted.enabled()) response.setHeader("Cache-Control", "no-store");
        boolean protectedPath =
                (path.startsWith("/api/") && !path.equals("/api/config"))
                        || path.equals("/mcp")
                        || (path.startsWith("/actuator/") && !path.equals("/actuator/health"));
        if (protectedPath) {
            response.setHeader("Cache-Control", "no-store");
            String role = accessRole;
            if (role == null) {
                response.setHeader("WWW-Authenticate", "Bearer");
                deny(response, 401, "AUTH_REQUIRED", "Use an operator or agent bearer token.");
                return;
            }
            request.setAttribute("accessRole", role);
            boolean read = request.getMethod().equals("GET");
            boolean proposal =
                    path.equals("/mcp")
                            || path.equals("/api/runs")
                            || path.equals("/api/plans")
                            || path.matches("/api/environments/[^/]+/reconcile")
                            || path.equals("/api/code/runs");
            if (role.equals("agent") && !read && !proposal) {
                deny(
                        response,
                        403,
                        "OPERATOR_REQUIRED",
                        "This action requires the operator credential.");
                return;
            }
        }
        if (request.getContentLengthLong() > 1_048_576) {
            deny(response, 413, "REQUEST_TOO_LARGE", "Requests are limited to 1 MiB.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void deny(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter()
                .write(
                        "{\"code\":\""
                                + code
                                + "\",\"message\":\""
                                + message
                                + "\",\"details\":[]}");
    }
}
