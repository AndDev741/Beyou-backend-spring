package beyou.beyouapp.backend.unit.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import beyou.beyouapp.backend.security.aiconsent.AiConsentInterceptor;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.enums.AiConsent;

class AiConsentInterceptorTest {

    private final AiConsentInterceptor interceptor = new AiConsentInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(AiConsent consent) {
        User user = new User();
        user.setAiConsent(consent);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1" + path);
        request.setContextPath("/api/v1");
        return request;
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /ai/agent/chats/0b9e/stream",
            "POST, /onboarding/suggestions",
            "POST, /notebook/ai/pages/1/chat",
            "POST, /notebook/ai/drafts",
            "POST, /notebook/pages/1/sources/text",
            "POST, /second-brain/questions/refill",
            "POST, /second-brain/questions/7/answer",
            "POST, /second-brain/insight/refresh",
    })
    void aDeclinedPersonIsTurnedAwayFromEveryRouteThatReachesTheProvider(String method, String path)
            throws Exception {
        signIn(AiConsent.DECLINED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request(method, path), response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"errorKey\":\"AI_CONSENT_DECLINED\"");
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /ai/agent/chats",
            "POST, /ai/agent/chats",
            "DELETE, /ai/agent/chats/0b9e",
            "GET, /second-brain",
            "POST, /second-brain/questions/7/skip",
            "GET, /daily-briefing",
            "POST, /habit",
    })
    void aDeclinedPersonKeepsEverythingThatStaysInBeyou(String method, String path) throws Exception {
        signIn(AiConsent.DECLINED);

        assertThat(interceptor.preHandle(request(method, path), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    void unaskedAndGrantedPass() throws Exception {
        signIn(AiConsent.UNASKED);
        assertThat(interceptor.preHandle(request("POST", "/onboarding/suggestions"),
                new MockHttpServletResponse(), new Object())).isTrue();

        signIn(AiConsent.GRANTED);
        assertThat(interceptor.preHandle(request("POST", "/onboarding/suggestions"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void noSignedInPersonIsLeftToSecurity() throws Exception {
        assertThat(interceptor.preHandle(request("POST", "/onboarding/suggestions"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }
}
