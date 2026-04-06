package beyou.beyouapp.backend.unit.security;

import beyou.beyouapp.backend.security.ratelimit.RateLimitConfig;
import beyou.beyouapp.backend.security.ratelimit.RateLimitFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private Cache<String, Bucket> cache;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();
        filter = new RateLimitFilter(cache);
        filterChain = mock(FilterChain.class);
    }

    @Test
    void shouldAllowAuthRequestsWithinLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldBlockAuthRequestsOverLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
            req.setRemoteAddr("192.168.1.100");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(req, resp, filterChain);
        }

        MockHttpServletRequest sixthRequest = new MockHttpServletRequest("POST", "/auth/login");
        sixthRequest.setRemoteAddr("192.168.1.100");
        MockHttpServletResponse sixthResponse = new MockHttpServletResponse();
        filter.doFilterInternal(sixthRequest, sixthResponse, filterChain);

        assertEquals(429, sixthResponse.getStatus());
        assertNotNull(sixthResponse.getHeader("Retry-After"));
    }

    @Test
    void shouldReturnRateLimitRemainingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertEquals("4", response.getHeader("X-Rate-Limit-Remaining"));
    }
}
