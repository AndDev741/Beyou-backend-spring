package beyou.beyouapp.backend.unit.security;

import beyou.beyouapp.backend.security.ratelimit.RateLimitFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import beyou.beyouapp.backend.user.User;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

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

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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

    // A POST on a chat calls the model, so it belongs to the hourly agent budget and not
    // to the 30-per-minute write bucket. Asserting the hourly number is the point: the
    // 31st request inside one minute proves the request was not merely throttled by the
    // write bucket, which would have allowed all 30 again the following minute.
    @Test
    void shouldSpendTheHourlyAgentBudgetOnAChatPost() throws Exception {
        authenticateUser();

        for (int i = 0; i < 30; i++) {
            MockHttpServletResponse allowed = callFilter("POST", "/ai/agent/chats/" + UUID.randomUUID() + "/stream");
            assertEquals(200, allowed.getStatus());
        }

        MockHttpServletResponse blocked = callFilter("POST", "/ai/agent/chats/" + UUID.randomUUID() + "/stream");

        assertEquals(429, blocked.getStatus());
        assertNotNull(blocked.getHeader("Retry-After"));
    }

    // One budget per user, not per chat: a fresh chat id must not hand out a fresh 30.
    // Creating a chat is cheap, so opening a new one is the first bypass anyone reaches
    // for once they notice the questions running out.
    @Test
    void shouldShareOneAgentBudgetAcrossChats() throws Exception {
        authenticateUser();
        UUID firstChat = UUID.randomUUID();
        UUID secondChat = UUID.randomUUID();

        for (int i = 0; i < 30; i++) {
            callFilter("POST", "/ai/agent/chats/" + firstChat + "/stream");
        }

        MockHttpServletResponse onAnotherChat = callFilter("POST", "/ai/agent/chats/" + secondChat + "/stream");

        assertEquals(429, onAnotherChat.getStatus());
    }

    // This is the gap the change closes. A POST straight at the chat, with no /stream
    // suffix, used to miss the agent branch and land in the per-minute write bucket —
    // 60x the budget, on the same model and the same bill. No endpoint answers this
    // shape today; the assertion exists so that adding one cannot quietly reopen it.
    @Test
    void shouldSpendTheAgentBudgetOnAChatPostWithoutTheStreamSuffix() throws Exception {
        authenticateUser();
        UUID chatId = UUID.randomUUID();

        for (int i = 0; i < 30; i++) {
            callFilter("POST", "/ai/agent/chats/" + chatId);
        }

        MockHttpServletResponse onTheStream = callFilter("POST", "/ai/agent/chats/" + chatId + "/stream");

        assertEquals(429, onTheStream.getStatus(),
                "a chat POST without /stream did not spend the agent budget");
    }

    // Renaming and deleting a chat never reach the model, and neither does creating one,
    // so none of them may eat the agent budget — otherwise tidying up the chat list
    // costs the user their questions.
    @Test
    void shouldNotSpendTheAgentBudgetOnNonModelChatRequests() throws Exception {
        authenticateUser();
        UUID chatId = UUID.randomUUID();

        callFilter("PUT", "/ai/agent/chats/" + chatId);
        callFilter("DELETE", "/ai/agent/chats/" + chatId);
        callFilter("POST", "/ai/agent/chats");

        for (int i = 0; i < 30; i++) {
            MockHttpServletResponse allowed = callFilter("POST", "/ai/agent/chats/" + chatId + "/stream");
            assertEquals(200, allowed.getStatus(), "agent budget was consumed by a non-model request");
        }
    }

    private void authenticateUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private MockHttpServletResponse callFilter(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, filterChain);
        return response;
    }
}
