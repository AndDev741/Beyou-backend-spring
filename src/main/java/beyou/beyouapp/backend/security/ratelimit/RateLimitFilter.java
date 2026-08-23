package beyou.beyouapp.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private final Cache<String, Bucket> rateLimitCache;
    private final MeterRegistry meterRegistry;

    /**
     * Counter for requests this filter turned away, tagged with the tier only.
     *
     * <p>Deliberately not tagged with the bucket key. The key carries a user id or an
     * address, so tagging it would give the metric unbounded cardinality — and an
     * attacker rotating addresses would be minting Prometheus series, which is a denial
     * of service against the monitoring rather than a defence of the app. The tier is a
     * closed set of about ten values, so it is safe to keep forever.
     *
     * <p>Identity lives in the log line instead: metrics answer "which limit is biting,
     * how often", the log answers "who". Splitting them that way is what keeps the
     * dashboard cheap and the forensics possible.
     */
    public static final String REJECTED_METRIC = "beyou.ratelimit.rejected";

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

    /**
     * A POST under an individual chat is a call into the LLM, and those get the hourly
     * agent bucket instead of the generic per-minute write bucket.
     *
     * <p>This matches the whole {@code /ai/agent/chats/{id}/**} subtree rather than the
     * {@code /stream} suffix it used to test. That suffix was chosen around the
     * transport — the thinking being that a long-lived SSE connection is the expensive
     * thing — but what needs bounding is tokens spent per user, not sockets held open.
     * A sibling non-streaming endpoint on the same chat consequently fell through to
     * {@code write:} and got 30/minute rather than 30/hour: the same model and the same
     * bill, on 60x the intended budget. Matching the subtree means the next endpoint
     * added here cannot reopen that gap merely by not being called {@code /stream}.
     *
     * <p>The caller gates this on POST. {@code PUT} renames a chat and {@code DELETE}
     * removes one; neither reaches the model, so neither should spend the LLM budget.
     * Matching broadly does mean a future non-LLM POST in this subtree would consume
     * agent quota it does not need — the safe direction to err, because the opposite
     * leaves LLM calls effectively unthrottled.
     *
     * <p>{@code POST /ai/agent/chats} (chat creation) is excluded: the prefix requires
     * the trailing slash, and creating a chat does not call the model.
     */
    private static boolean isAgentLlmPath(String path) {
        return path.startsWith("/ai/agent/chats/");
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
        } else if ("POST".equals(method) && isAgentLlmPath(path)) {
            String userId = getUserIdFromRequest(request);
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            // One key for every LLM entry point on a chat, so alternating between
            // endpoints cannot hand the caller a second budget of the same size.
            bucketKey = "agent:" + userId;
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
        } else if ("GET".equals(method) && path.equals("/user/export")) {
            // Ahead of the generic read branch on purpose. This GET returns the entire
            // account in one response, so it costs nothing like the list reads the read
            // bucket is sized for, and 60/minute of it is a way to hold the heap.
            String userId = getUserIdFromRequest(request);
            if (userId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "export:" + userId;
            bucket = rateLimitCache.get(bucketKey, k -> RateLimitConfig.createUserExportBucket());
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

            // A rejection used to leave no trace at all: it is not an exception, so the
            // error tracker never sees it, and nothing here logged or counted. That made
            // throttling invisible in both directions — you could not tell a user being
            // turned away from a limit that never bit, and the only way to find out was
            // for someone to complain.
            int separator = bucketKey.indexOf(':');
            String tier = separator < 0 ? "unknown" : bucketKey.substring(0, separator);
            meterRegistry.counter(REJECTED_METRIC, "tier", tier).increment();
            // WARN, not INFO: every line here is a request that did not happen, which is
            // either abuse worth seeing or a limit sized too tightly. The identity is the
            // whole point of the line — it is what the metric cannot carry.
            log.warn("Rate limit rejected {} {} — tier={} key={} retryAfter={}s",
                    method, path, tier, bucketKey, waitSeconds);

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
