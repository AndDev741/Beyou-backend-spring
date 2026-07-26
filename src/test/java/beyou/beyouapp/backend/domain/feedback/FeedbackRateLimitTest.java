package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.security.ratelimit.RateLimitConfig;
import beyou.beyouapp.backend.security.ratelimit.RateLimitFilter;
import beyou.beyouapp.backend.user.User;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * R22/KTD9 — feedback submission is rate limited per user, from its own bucket
 * inserted ahead of the generic domain-write branch, so a burst of feedback
 * cannot eat the user's routine write budget (and vice versa).
 */
class FeedbackRateLimitTest {

    private RateLimitFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        Cache<String, Bucket> cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();
        filter = new RateLimitFilter(cache);
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("submissions past the per-user budget are rejected while another user still succeeds")
    void budgetIsPerUser() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        for (int i = 0; i < RateLimitConfig.FEEDBACK_SUBMISSIONS_PER_HOUR; i++) {
            assertThat(submitFeedbackAs(first).getStatus())
                    .as("submission %d of the budget should pass", i + 1)
                    .isEqualTo(200);
        }

        assertThat(submitFeedbackAs(first).getStatus())
                .as("the submission past the budget is rejected")
                .isEqualTo(429);

        assertThat(submitFeedbackAs(second).getStatus())
                .as("a different user's submission still succeeds")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("an exhausted feedback budget does not consume the user's domain write budget")
    void feedbackBudgetIsSeparateFromTheGenericWriteBudget() throws Exception {
        UUID userId = UUID.randomUUID();

        for (int i = 0; i < RateLimitConfig.FEEDBACK_SUBMISSIONS_PER_HOUR + 1; i++) {
            submitFeedbackAs(userId);
        }

        MockHttpServletResponse domainWrite = perform(userId, "POST", "/category");

        assertThat(domainWrite.getStatus())
                .as("routine domain writes keep their own budget")
                .isEqualTo(200);
    }

    private MockHttpServletResponse submitFeedbackAs(UUID userId) throws Exception {
        return perform(userId, "POST", "/feedback");
    }

    private MockHttpServletResponse perform(UUID userId, String method, String path) throws Exception {
        authenticate(userId);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, filterChain);
        return response;
    }

    private void authenticate(UUID userId) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
