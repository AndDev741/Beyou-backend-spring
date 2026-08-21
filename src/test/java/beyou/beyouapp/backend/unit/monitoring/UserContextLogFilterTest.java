package beyou.beyouapp.backend.unit.monitoring;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import beyou.beyouapp.backend.monitoring.UserContextLogFilter;
import beyou.beyouapp.backend.user.User;
import jakarta.servlet.FilterChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The filter's whole job is a value visible to log statements DURING the request and gone
 * after it, so every assertion is made from inside the chain — asserting after
 * {@code doFilter} returns would pass on a filter that never set anything.
 */
class UserContextLogFilterTest {

    private final UserContextLogFilter filter = new UserContextLogFilter();

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private static void authenticate(UUID userId) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    /** Captures what a log statement inside the request would have seen. */
    private static FilterChain capturing(String[] seen) {
        return (request, response) -> seen[0] = MDC.get(UserContextLogFilter.USER_ID_KEY);
    }

    @Test
    void theAuthenticatedUsersIdIsVisibleToLogStatementsDuringTheRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(userId);
        String[] seen = new String[1];

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/habit"), new MockHttpServletResponse(),
                capturing(seen));

        assertEquals(userId.toString(), seen[0]);
    }

    @Test
    void theIdIsRemovedWhenTheRequestFinishes() throws Exception {
        authenticate(UUID.randomUUID());

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/habit"), new MockHttpServletResponse(),
                (request, response) -> {
                });

        assertNull(MDC.get(UserContextLogFilter.USER_ID_KEY),
                "a request must not leave its user id behind on the thread");
    }

    @Test
    void theIdIsRemovedEvenWhenTheRequestBlowsUp() {
        authenticate(UUID.randomUUID());

        try {
            filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/habit"), new MockHttpServletResponse(),
                    (request, response) -> {
                        throw new IllegalStateException("boom");
                    });
        } catch (Exception expected) {
            // The filter must not swallow it; the point here is the MDC state afterwards.
        }

        assertNull(MDC.get(UserContextLogFilter.USER_ID_KEY),
                "a failed request must not leave its user id behind on the thread");
    }

    @Test
    void anUnauthenticatedRequestCarriesNoId() throws Exception {
        String[] seen = new String[1];

        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/login"), new MockHttpServletResponse(),
                capturing(seen));

        assertNull(seen[0], "with no id in the MDC the log pattern renders its `anonymous` default");
    }

    /**
     * The case that would mislabel one user's activity as another's: a pooled thread whose
     * previous request left a value behind (possible the moment virtual threads are switched
     * off). An unauthenticated request must clear it, not inherit it.
     */
    @Test
    void anUnauthenticatedRequestDoesNotInheritTheThreadsPreviousUser() throws Exception {
        MDC.put(UserContextLogFilter.USER_ID_KEY, UUID.randomUUID().toString());
        String[] seen = new String[1];

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/docs"), new MockHttpServletResponse(),
                capturing(seen));

        assertNull(seen[0]);
    }
}
