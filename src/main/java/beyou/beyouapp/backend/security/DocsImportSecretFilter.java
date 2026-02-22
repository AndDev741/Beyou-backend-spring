package beyou.beyouapp.backend.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
        String path = request.getRequestURI();
        if(path == null) return true;

        boolean isDocsImport = path.startsWith("/docs/admin/import");
        boolean isActuator = path.startsWith("/actuator");

        if (!isDocsImport && !isActuator) {
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
        if (providedSecret == null || !providedSecret.equals(importSecret)) {
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
