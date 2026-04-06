package beyou.beyouapp.backend.unit.security;

import beyou.beyouapp.backend.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenServiceCookieTest {

    @Test
    void cookieShouldUseConfiguredSameSiteValue() {
        TokenService service = new TokenService();
        ReflectionTestUtils.setField(service, "secret", "test-secret-that-is-at-least-32-characters-long");
        ReflectionTestUtils.setField(service, "COOKIE_SECURE", false);
        ReflectionTestUtils.setField(service, "cookieSameSite", "Strict");

        ResponseCookie cookie = service.buildRefreshCookie("test-value", Duration.ofDays(1));

        assertTrue(cookie.toString().contains("SameSite=Strict"));
    }

    @Test
    void cookieShouldDefaultToLax() {
        TokenService service = new TokenService();
        ReflectionTestUtils.setField(service, "secret", "test-secret-that-is-at-least-32-characters-long");
        ReflectionTestUtils.setField(service, "COOKIE_SECURE", false);
        ReflectionTestUtils.setField(service, "cookieSameSite", "Lax");

        ResponseCookie cookie = service.buildRefreshCookie("test-value", Duration.ofDays(1));

        assertTrue(cookie.toString().contains("SameSite=Lax"));
    }
}
