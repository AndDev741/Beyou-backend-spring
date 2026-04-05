package beyou.beyouapp.backend.unit.security;

import beyou.beyouapp.backend.security.SecurityConfigValidator;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityConfigValidatorTest {

    @Test
    void shouldRejectWildcardCorsInProdProfile() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityConfigValidator validator = new SecurityConfigValidator(
                env,
                "*",
                "a-secret-that-is-at-least-32-characters-long-ok",
                true
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assert ex.getMessage().contains("CORS");
    }

    @Test
    void shouldRejectShortTokenSecret() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityConfigValidator validator = new SecurityConfigValidator(
                env,
                "https://beyou.app",
                "short",
                true
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assert ex.getMessage().contains("TOKEN_SECRET");
    }

    @Test
    void shouldRejectCookieSecureFalseInProd() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityConfigValidator validator = new SecurityConfigValidator(
                env,
                "https://beyou.app",
                "a-secret-that-is-at-least-32-characters-long-ok",
                false
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assert ex.getMessage().contains("COOKIE_SECURE");
    }

    @Test
    void shouldPassValidProdConfig() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityConfigValidator validator = new SecurityConfigValidator(
                env,
                "https://beyou.app",
                "a-secret-that-is-at-least-32-characters-long-ok",
                true
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldSkipValidationInDevProfile() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});

        SecurityConfigValidator validator = new SecurityConfigValidator(
                env,
                "*",
                "short",
                false
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldSkipValidationWhenNoProfile() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{});

        SecurityConfigValidator validator = new SecurityConfigValidator(
                env,
                "*",
                "short",
                false
        );

        assertDoesNotThrow(validator::validate);
    }
}
