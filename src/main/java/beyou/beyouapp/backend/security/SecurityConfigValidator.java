package beyou.beyouapp.backend.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@Slf4j
@Configuration
public class SecurityConfigValidator {

    private final Environment env;
    private final String corsAllowedPattern;
    private final String tokenSecret;
    private final boolean cookieSecure;

    public SecurityConfigValidator(
            Environment env,
            @Value("${cors.allowed-pattern}") String corsAllowedPattern,
            @Value("${api.security.token.secret}") String tokenSecret,
            @Value("${cookie.secure}") boolean cookieSecure
    ) {
        this.env = env;
        this.corsAllowedPattern = corsAllowedPattern;
        this.tokenSecret = tokenSecret;
        this.cookieSecure = cookieSecure;
    }

    @PostConstruct
    public void validate() {
        if (!isProdProfile()) {
            log.info("[SECURITY] Non-prod profile — skipping startup security validation");
            return;
        }

        log.info("[SECURITY] Running prod profile startup security validation...");

        if ("*".equals(corsAllowedPattern)) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_PATTERN must not be '*' in production. " +
                    "Set it to your explicit frontend domain (e.g., https://beyou.app)."
            );
        }

        if (tokenSecret == null || tokenSecret.length() < 32) {
            throw new IllegalStateException(
                    "TOKEN_SECRET must be at least 32 characters in production. " +
                    "Generate one with: openssl rand -base64 48"
            );
        }

        if (!cookieSecure) {
            throw new IllegalStateException(
                    "COOKIE_SECURE must be true in production. " +
                    "Set the COOKIE_SECURE environment variable to true."
            );
        }

        log.info("[SECURITY] Prod startup security validation passed");
    }

    private boolean isProdProfile() {
        return Arrays.asList(env.getActiveProfiles()).contains("prod");
    }
}
