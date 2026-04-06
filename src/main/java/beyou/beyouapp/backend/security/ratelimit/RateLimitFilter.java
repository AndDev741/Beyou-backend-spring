package beyou.beyouapp.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@Order(1)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private final Cache<String, Bucket> rateLimitCache;

    private static final Set<String> AUTH_PATHS = Set.of(
            "/auth/login", "/auth/register", "/auth/forgot-password"
    );

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    @Override
    public void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        String bucketKey;
        Bucket bucket;

        if (AUTH_PATHS.contains(path)) {
            String ip = getClientIp(request);
            bucketKey = "auth:" + ip;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createAuthBucket());
        } else if (path.startsWith("/docs") && !path.startsWith("/docs/admin")) {
            String ip = getClientIp(request);
            bucketKey = "docs:" + ip;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createDocsBucket());
        } else if (WRITE_METHODS.contains(method)) {
            String userId = getUserIdFromRequest(request);
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "write:" + userId;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createDomainWriteBucket());
        } else if ("GET".equals(method) && !path.startsWith("/auth")) {
            String userId = getUserIdFromRequest(request);
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "read:" + userId;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createDomainReadBucket());
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.addHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            long waitSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000;
            response.setStatus(429);
            response.addHeader("Retry-After", String.valueOf(waitSeconds));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests. Retry after " + waitSeconds + " seconds.\"}");
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String getUserIdFromRequest(HttpServletRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof beyou.beyouapp.backend.user.User user) {
            return user.getId().toString();
        }
        return null;
    }
}
