package beyou.beyouapp.backend.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class DocsImportSecretFilter extends OncePerRequestFilter {

    @Value("${docs.import.secret:}")
    private String importSecret;

    @Override
    protected boolean shouldNotFilter(@SuppressWarnings("null") HttpServletRequest request) {
        // Strip the servlet context-path (e.g. /api/v1) so the /docs/admin/import
        // comparison works regardless of versioning. Done manually because
        // getServletPath() returns an empty string under MockMvc.
        String uri = request.getRequestURI();
        if (uri == null) return true;
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;

        boolean isDocsImport = path.startsWith("/docs/admin/import");

        if (!isDocsImport) {
            return true;
        }

        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
        @SuppressWarnings("null") HttpServletRequest request,
        @SuppressWarnings("null") HttpServletResponse response,
        @SuppressWarnings("null") FilterChain filterChain
    ) throws ServletException, IOException {
        if (importSecret == null || importSecret.isBlank()) {
            setForbidden(response, "Docs import secret not configured");
            return;
        }

        String providedSecret = request.getHeader("X-Docs-Import-Secret");
        if (providedSecret == null || !MessageDigest.isEqual(providedSecret.getBytes(StandardCharsets.UTF_8), importSecret.getBytes(StandardCharsets.UTF_8))) {
            setForbidden(response, "Docs import secret invalid");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void setForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(message);
        response.getWriter().flush();
    }
}
