package beyou.beyouapp.backend.security.validators;

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
    private final boolean exposeDeletionCode;
    private final boolean autoVerifyEmail;
    private final boolean exposeVerificationToken;

    public SecurityConfigValidator(
            Environment env,
            @Value("${cors.allowed-pattern}") String corsAllowedPattern,
            @Value("${api.security.token.secret}") String tokenSecret,
            @Value("${cookie.secure}") boolean cookieSecure,
            @Value("${e2e.expose-deletion-code:false}") boolean exposeDeletionCode,
            @Value("${e2e.auto-verify-email:false}") boolean autoVerifyEmail,
            @Value("${e2e.expose-verification-token:false}") boolean exposeVerificationToken
    ) {
        this.env = env;
        this.corsAllowedPattern = corsAllowedPattern;
        this.tokenSecret = tokenSecret;
        this.cookieSecure = cookieSecure;
        this.exposeDeletionCode = exposeDeletionCode;
        this.autoVerifyEmail = autoVerifyEmail;
        this.exposeVerificationToken = exposeVerificationToken;
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

        // Both are set in application-e2e.yml and nowhere else, so profile composition
        // cannot reach them. A bare environment variable can: relaxed binding resolves
        // E2E_EXPOSE_DELETION_CODE=true under any profile, silently. The first hands
        // the plaintext deletion code back in the response, which disarms the only gate
        // on an irreversible action for every existing account at once; the second
        // marks new accounts verified without an email.
        if (exposeDeletionCode) {
            throw new IllegalStateException(
                    "E2E_EXPOSE_DELETION_CODE must not be true in production. " +
                    "It returns the account deletion code in the response body, which is the " +
                    "only thing standing between a stolen session and a deleted account."
            );
        }

        if (autoVerifyEmail) {
            throw new IllegalStateException(
                    "E2E_AUTO_VERIFY_EMAIL must not be true in production. " +
                    "It marks new accounts as verified without anyone reading the email."
            );
        }

        // The third of the same family, and the worst of them if it ever escaped: it
        // hands back the token that marks an address verified, and it lets the caller
        // ask for an account that skips auto-verification, so a registration could be
        // walked to a verified account for an address nobody owns.
        if (exposeVerificationToken) {
            throw new IllegalStateException(
                    "E2E_EXPOSE_VERIFICATION_TOKEN must not be true in production. " +
                    "It returns the email-verification token in the response body, which is " +
                    "the whole proof that somebody owns the address they signed up with."
            );
        }

        log.info("[SECURITY] Prod startup security validation passed");
    }

    private boolean isProdProfile() {
        return Arrays.asList(env.getActiveProfiles()).contains("prod");
    }
}
