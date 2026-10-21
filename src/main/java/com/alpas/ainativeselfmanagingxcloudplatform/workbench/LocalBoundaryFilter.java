package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local demo boundary, not an authentication system. Blocks foreign Host/Origin and framing. */
@Component
public class LocalBoundaryFilter extends OncePerRequestFilter {
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        boolean permitted = LOCAL.contains(request.getServerName());
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                var source = URI.create(origin);
                int port = source.getPort() == -1 ? ("https".equals(source.getScheme()) ? 443 : 80) : source.getPort();
                permitted &= LOCAL.contains(source.getHost()) && source.getHost().equals(request.getServerName())
                        && source.getScheme().equals(request.getScheme()) && port == request.getServerPort();
            } catch (RuntimeException malformed) { permitted = false; }
        }
        if (!permitted) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"LOCAL_ORIGIN_REQUIRED\",\"message\":\"Use the local workbench origin.\",\"details\":[]}");
            return;
        }
        chain.doFilter(request, response);
    }
}
