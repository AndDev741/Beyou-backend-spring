package beyou.beyouapp.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * The header the proxy in front sets to the caller's real address, or blank when
     * nothing is in front. Only a header a client cannot forge belongs here.
     */
    // Initialised as well as annotated: @Value only fills this when Spring builds the
    // filter, and the unit tests construct it directly. Without the default here that
    // is a null on the first request they push through.
    @Value("${rate-limit.trusted-client-ip-header:CF-Connecting-IP}")
    private String trustedClientIpHeader = "CF-Connecting-IP";

    private static final Set<String> AUTH_PATHS = Set.of(
            "/auth/login", "/auth/register", "/auth/forgot-password", "/auth/google", "/auth/google/mobile"
    );

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    /** AI agent chat streams get their own bucket, NOT the generic write bucket:
     *  each stream is an expensive long-lived LLM call. */
    private static boolean isAgentStreamPath(String path) {
        return path.startsWith("/ai/agent/chats/") && path.endsWith("/stream");
    }

    /** {@code POST /feedback/{feedbackId}/attachments} — see {@link RateLimitConfig#createFeedbackAttachmentBucket()}. */
    private static boolean isFeedbackAttachmentPath(String path) {
        return path.startsWith("/feedback/") && path.endsWith("/attachments");
    }

    @Override
    public void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
        // Strip the servlet context-path (e.g. /api/v1) so AUTH_PATHS / "/docs/*" /
        // "/auth/*" comparisons work regardless of versioning. Done manually because
        // getServletPath() returns an empty string under MockMvc.
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        String method = request.getMethod();

        String bucketKey;
        Bucket bucket;

        if (AUTH_PATHS.contains(path)) {
            String ip = getClientIp(request);
            bucketKey = "auth:" + ip;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createAuthBucket());
        } else if (isAgentStreamPath(path)) {
            String userId = getUserIdFromRequest(request);
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "agent-stream:" + userId;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createAgentChatBucket());
        } else if (path.startsWith("/docs") && !path.startsWith("/docs/admin")) {
            String ip = getClientIp(request);
            bucketKey = "docs:" + ip;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createDocsBucket());
        } else if ("GET".equals(method) && path.startsWith("/user/photo")) {
            // Public unauthenticated endpoint (no userId available) — throttle per IP
            // so anonymous callers can't flood disk-read requests for arbitrary UUIDs.
            String ip = getClientIp(request);
            bucketKey = "photo:" + ip;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createPhotoBucket());
        } else if ("POST".equals(method) && path.equals("/onboarding/suggestions")) {
            // LLM-backed onboarding suggestions share the agent-chat quota shape but their own key
            String userId = getUserIdFromRequest(request);
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "onboarding:" + userId;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createAgentChatBucket());
        } else if ("POST".equals(method) && path.startsWith("/user/deletion/")) {
            // Ahead of the generic write branch: a deletion code is six digits, and the
            // write bucket's 30/min is sized for habit check-ins, not for how fast
            // someone may guess at the only gate on an irreversible action.
            String userId = getUserIdFromRequest(request);
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "account-deletion:" + userId;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createAccountDeletionBucket());
        } else if ("POST".equals(method) && path.equals("/feedback")) {
            // Feedback submission gets its own per-user budget, ahead of the generic
            // write branch: a burst of submissions must not eat the user's habit/routine
            // write allowance, and the admin queue must not be floodable on it either.
            String userId = getUserIdFromRequest(request);
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "feedback:" + userId;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createFeedbackBucket());
        } else if ("POST".equals(method) && isFeedbackAttachmentPath(path)) {
            // Also ahead of the generic write branch, and for a different reason
            // than submission: each upload decodes, downscales and re-encodes an
            // image, so a handful in flight is tens of megabytes of transient
            // heap. That cost does not belong in a 30-per-minute write bucket.
            String userId = getUserIdFromRequest(request);
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "feedback-attachment:" + userId;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createFeedbackAttachmentBucket());
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
            response.getWriter().write(
                "{\"errorKey\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests. Retry after " + waitSeconds + " seconds.\"}");
        }
    }

    /**
     * Who this request is from, for the buckets keyed by address.
     *
     * <p>Not {@code X-Forwarded-For}. That header is a list any client can prepend to,
     * and Cloudflare APPENDS the real address rather than replacing the header — so its
     * leftmost entry, which this used to read, is whatever the caller typed. A remote
     * attacker sending a different value per request minted a fresh bucket every time
     * and never met the 5-per-15-minutes cap on login at all. It was the only automated
     * abuse control on that endpoint.
     *
     * <p>{@code CF-Connecting-IP} instead: the edge sets it and overwrites any inbound
     * copy, so a client cannot choose it. The name is configurable because the header
     * belongs to whatever proxy is actually in front — an install behind something else
     * points this at that proxy's equivalent, and one with no proxy at all leaves it
     * blank and gets the socket address.
     *
     * <p>The fallback is the socket address, and it is deliberately NOT a rightmost-hop
     * parse of the forwarded chain. Behind a tunnel every request shares one socket
     * address, so this bucket collapses into a single global one — which is why the
     * per-account lockout exists beside it. An IP bucket that cannot tell clients apart
     * must never be the only thing standing between an attacker and an account.
     */
    private String getClientIp(HttpServletRequest request) {
        if (trustedClientIpHeader != null && !trustedClientIpHeader.isBlank()) {
            String forwarded = request.getHeader(trustedClientIpHeader);
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.trim();
            }
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
