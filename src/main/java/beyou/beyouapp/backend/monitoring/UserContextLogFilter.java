package beyou.beyouapp.backend.monitoring;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import beyou.beyouapp.backend.user.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Puts the authenticated user's id in the logging context for the length of a request,
 * so every line the request produces carries who it was for.
 *
 * <p>The aspects in {@code AOP/} log method names and durations, never arguments — a
 * deliberate choice, because the arguments are habit and goal text. That leaves the logs
 * unable to answer the first question asked of them in an incident: whether one account
 * is looping or a hundred are. The id closes that gap without putting any user content
 * in the line: the MDC value is the primary key, and turning it back into a person needs
 * database access.
 *
 * <p>Read by the {@code logging.pattern.correlation} pattern in {@code application.yaml},
 * which is Spring Boot's supported slot for exactly this and so survives a change to the
 * default console pattern. Lines with no user — startup, the snapshot scheduler,
 * unauthenticated calls — render {@code anonymous} from the pattern's default rather than
 * a blank, so the field is present on every line and a log query never has to cope with
 * two shapes.
 *
 * <p>Registered as a plain servlet filter (not into the Spring Security chain) at order 0.
 * Spring Security's {@code FilterChainProxy} sits at order -100, so by the time this runs
 * {@code SecurityFilter} has already put the principal in the context; order 0 also places
 * it ahead of {@code RateLimitFilter} (order 1), whose rejection WARN is one of the lines
 * that most needs the id.
 *
 * <p>Two kinds of line still say {@code anonymous} on an authenticated request, both by
 * construction rather than by omission:
 * <ul>
 *   <li>{@code TokenService.validateToken}, logged by the aspects from inside
 *       {@code SecurityFilter}. It runs before the token has been turned into a user, so
 *       there is no id in existence yet to attach.</li>
 *   <li>the agent SSE stream. {@code OncePerRequestFilter} does not run on async
 *       re-dispatch, and the tokens are emitted from a reactor thread that never had this
 *       MDC. {@code AiAgentService} is handed the user id explicitly and logs it where it
 *       matters.</li>
 * </ul>
 * The first is pinned by {@code UserIdLogPatternTest}, which excludes exactly that one
 * method and requires every other line to carry the id.
 *
 * <p>Side effect worth knowing: with the Logback integration on, Sentry copies the MDC onto
 * breadcrumbs and events, so the id reaches the error collector too. That is wanted — it is
 * what answers "one account or all of them?" on an incident — and it does not breach the
 * no-PII rule the collector is configured for: the value is a surrogate key, the collector
 * is self-hosted, and nothing a user wrote travels with it.
 */
@Component
@Order(0)
public class UserContextLogFilter extends OncePerRequestFilter {

    /** MDC key. Matches the {@code %X{userId}} lookup in the log pattern. */
    public static final String USER_ID_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = currentUserId();

        // Cleared as well as set: virtual threads make a leftover value from an earlier
        // request impossible, but virtual threads are a property that can be switched
        // off, and a pooled thread carrying the previous caller's id would attribute one
        // user's activity to another — the one failure mode worse than having no id.
        if (userId == null) {
            MDC.remove(USER_ID_KEY);
        } else {
            MDC.put(USER_ID_KEY, userId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(USER_ID_KEY);
        }
    }

    private static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId().toString();
        }

        return null;
    }
}
