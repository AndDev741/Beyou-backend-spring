package beyou.beyouapp.backend.unit.security;

import beyou.beyouapp.backend.security.ratelimit.RateLimitConfig;
import beyou.beyouapp.backend.security.ratelimit.RateLimitFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private Cache<String, Bucket> cache;
    private FilterChain filterChain;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();
        meterRegistry = new SimpleMeterRegistry();
        filter = new RateLimitFilter(cache, meterRegistry);
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

    // A rejection is not an exception, so nothing outside this filter can observe one.
    // Without the counter the only report that a limit ever bit was a user complaining.
    @Test
    void shouldCountRejectionsUnderTheTierThatRejected() throws Exception {
        for (int i = 0; i < 5; i++) {
            callFilter("POST", "/auth/login");
        }

        callFilter("POST", "/auth/login");

        assertEquals(1.0, meterRegistry.counter(RateLimitFilter.REJECTED_METRIC, "tier", "auth").count());
    }

    // The tag is the tier and never the bucket key: the key holds a user id or an address,
    // so tagging it would let a caller rotating addresses mint Prometheus series at will.
    @Test
    void shouldTagRejectionsByTierRatherThanIdentity() throws Exception {
        authenticateUser();
        UUID chatId = UUID.randomUUID();

        for (int i = 0; i < 31; i++) {
            callFilter("POST", "/ai/agent/chats/" + chatId + "/stream");
        }

        assertEquals(1.0, meterRegistry.counter(RateLimitFilter.REJECTED_METRIC, "tier", "agent").count());
        assertTrue(meterRegistry.find(RateLimitFilter.REJECTED_METRIC).counters().stream()
                        .flatMap(c -> c.getId().getTags().stream())
                        .noneMatch(tag -> tag.getValue().contains(":")),
                "a bucket key leaked into a metric tag");
    }

    @Test
    void shouldNotCountAnythingWhenNoLimitIsHit() throws Exception {
        authenticateUser();

        callFilter("POST", "/ai/agent/chats/" + UUID.randomUUID() + "/stream");

        assertNull(meterRegistry.find(RateLimitFilter.REJECTED_METRIC).counter());
    }

    // GET /user/export returns the whole account in one response, so it must not be
    // spending the generic read budget that list screens use. Six inside an hour is one
    // more than the tier allows, and a caller on the read tier would sail past it.
    @Test
    void shouldGiveTheDataExportItsOwnHourlyBudget() throws Exception {
        authenticateUser();

        for (int i = 0; i < RateLimitConfig.USER_EXPORT_DOWNLOADS_PER_HOUR; i++) {
            assertEquals(200, callFilter("GET", "/user/export").getStatus(),
                    "an export inside the hourly allowance was turned away");
        }

        MockHttpServletResponse overLimit = callFilter("GET", "/user/export");

        assertEquals(429, overLimit.getStatus(),
                "the export is still on the 60-per-minute read budget");
        assertNotNull(overLimit.getHeader("Retry-After"));
        assertEquals(1.0, meterRegistry.counter(RateLimitFilter.REJECTED_METRIC, "tier", "export").count());
    }

    // The export tier sits ahead of the read branch, so the reverse has to hold too:
    // spending the export budget must not cost the user the reads their screens make.
    @Test
    void shouldNotSpendTheReadBudgetOnTheDataExport() throws Exception {
        authenticateUser();

        for (int i = 0; i < RateLimitConfig.USER_EXPORT_DOWNLOADS_PER_HOUR; i++) {
            callFilter("GET", "/user/export");
        }

        for (int i = 0; i < 60; i++) {
            assertEquals(200, callFilter("GET", "/habit").getStatus(),
                    "exporting the account ate the ordinary read allowance");
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
