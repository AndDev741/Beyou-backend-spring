package beyou.beyouapp.backend.unit.security;

import beyou.beyouapp.backend.security.validators.SecurityConfigValidator;
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
                true,
                false,
                false
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
                true,
                false,
                false
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
                false,
                false,
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
                true,
                false,
                false
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
                false,
                false,
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
                false,
                false,
                false
        );

        assertDoesNotThrow(validator::validate);
    }

    /**
     * Profile composition cannot reach these — both live in application-e2e.yml only.
     * A bare E2E_EXPOSE_DELETION_CODE=true resolves under any profile through relaxed
     * binding, with no other signal at boot, and it hands the plaintext deletion code
     * back in the response.
     */
    @Test
    void shouldRejectTheExposedDeletionCodeInProdProfile() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityConfigValidator validator = new SecurityConfigValidator(
                env,
                "https://beyou.app",
                "a-secret-that-is-at-least-32-characters-long-ok",
                true,
                true,
                false
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assert ex.getMessage().contains("E2E_EXPOSE_DELETION_CODE");
    }

    @Test
    void shouldRejectAutoVerifiedEmailsInProdProfile() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityConfigValidator validator = new SecurityConfigValidator(
                env,
                "https://beyou.app",
                "a-secret-that-is-at-least-32-characters-long-ok",
                true,
                false,
                true
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assert ex.getMessage().contains("E2E_AUTO_VERIFY_EMAIL");
    }
}
