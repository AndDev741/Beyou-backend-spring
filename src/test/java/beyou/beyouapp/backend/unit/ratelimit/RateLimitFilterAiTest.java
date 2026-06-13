package beyou.beyouapp.backend.unit.ratelimit;

import beyou.beyouapp.backend.security.ratelimit.RateLimitFilter;
import beyou.beyouapp.backend.user.User;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitFilterAiTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        Cache<String, Bucket> cache = Caffeine.newBuilder().maximumSize(100).build();
        filter = new RateLimitFilter(cache);
        User user = new User();
        user.setId(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse fire(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1" + path);
        request.setContextPath("/api/v1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void eleventhGenerateCallWithinAnHourIs429() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertEquals(200, fire("/ai/routine/generate").getStatus(), "call " + (i + 1));
        }
        assertEquals(429, fire("/ai/routine/generate").getStatus());
    }

    @Test
    void confirmUsesTheNormalWriteBucketNotTheAiBucket() throws Exception {
        // 11 confirms stay under the 30/min write bucket
        for (int i = 0; i < 11; i++) {
            assertEquals(200, fire("/ai/routine/confirm").getStatus(), "call " + (i + 1));
        }
    }
}
