package beyou.beyouapp.backend.unit.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import beyou.beyouapp.backend.security.aiconsent.AiConsentInterceptor;
import beyou.beyouapp.backend.security.aiconsent.SendsToAiProvider;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.enums.AiConsent;

class AiConsentInterceptorTest {

    private final AiConsentInterceptor interceptor = new AiConsentInterceptor();

    /** Stand-ins for a controller: one route that reaches the provider, one that does not. */
    static class Routes {
        @SendsToAiProvider
        public void askTheModel() {}

        public void listHabits() {}
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Routes(), Routes.class.getMethod(name));
    }

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

    private boolean pass(String route, MockHttpServletResponse response) throws Exception {
        return interceptor.preHandle(new MockHttpServletRequest("POST", "/api/v1/anything"), response, handler(route));
    }

    @ParameterizedTest
    @EnumSource(value = AiConsent.class, names = {"UNASKED", "DECLINED"})
    void nothingReachesTheProviderWithoutAYes(AiConsent consent) throws Exception {
        signIn(consent);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(pass("askTheModel", response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"errorKey\":\"AI_CONSENT_DECLINED\"");
    }

    @Test
    void aYesLetsTheRouteThrough() throws Exception {
        signIn(AiConsent.GRANTED);
        assertThat(pass("askTheModel", new MockHttpServletResponse())).isTrue();
    }

    @ParameterizedTest
    @EnumSource(AiConsent.class)
    void routesThatStayInBeyouAreNeverTouched(AiConsent consent) throws Exception {
        signIn(consent);
        assertThat(pass("listHabits", new MockHttpServletResponse())).isTrue();
    }

    @Test
    void noSignedInPersonIsLeftToSecurity() throws Exception {
        assertThat(pass("askTheModel", new MockHttpServletResponse())).isTrue();
    }
}
