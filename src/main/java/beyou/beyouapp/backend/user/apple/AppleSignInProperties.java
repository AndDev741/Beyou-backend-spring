package beyou.beyouapp.backend.user.apple;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sign in with Apple, {@code apple.sign-in.*} in application.yaml.
 *
 * <p>Two independent halves:
 * <ul>
 *   <li><b>Signing in</b> needs only {@link #audiences}: the token is verified against
 *       Apple's published keys, which are public. It works out of the box for the iOS app
 *       ({@code com.beyou.mobile}).</li>
 *   <li><b>Revoking at account deletion</b> needs a Sign in with Apple key from the Apple
 *       Developer account ({@link #teamId}, {@link #keyId}, {@link #privateKey}). Without
 *       it the backend cannot get the refresh token Apple's revoke endpoint wants, so
 *       sign-in still works and deletion still deletes, and each skipped revocation says
 *       so in the log. See {@link AppleTokenClient}.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "apple.sign-in")
@Getter
@Setter
public class AppleSignInProperties {

    /** Apple's {@code iss}. Not a deployment choice; a property only so tests can point it at a stub. */
    private String issuer = "https://appleid.apple.com";

    /**
     * The {@code aud} values we accept: the iOS bundle id, and a Services ID if the web ever
     * offers Apple too. A token minted for any other app is refused.
     */
    private List<String> audiences = new ArrayList<>(List.of(DEFAULT_AUDIENCE));

    /** The iOS app's bundle id. */
    public static final String DEFAULT_AUDIENCE = "com.beyou.mobile";

    /** The Apple Developer team that owns the key below (10 characters). */
    private String teamId;

    /** The id of the Sign in with Apple key (10 characters, from the .p8's name). */
    private String keyId;

    /**
     * The .p8 key itself, PEM text. Newlines may be real or written as {@code \n}, so it fits
     * in one environment variable. A secret: never commit it, never log it.
     */
    private String privateKey;

    /** Base of Apple's token and revoke endpoints. A property only so tests can stub it. */
    private String apiBaseUrl = "https://appleid.apple.com";

    /**
     * The accepted audiences, trimmed. An {@code APPLE_AUDIENCES} that is set but empty (a
     * copied envExample line) binds to an empty list, which would refuse every sign-in; it
     * means "not set", so it falls back to the bundle id.
     */
    public List<String> getAudiences() {
        List<String> cleaned = audiences == null ? List.of() : audiences.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(String::trim)
                .toList();
        return cleaned.isEmpty() ? List.of(DEFAULT_AUDIENCE) : cleaned;
    }

    /** Whether the key needed to exchange codes and revoke tokens is configured. */
    public boolean isRevocationConfigured() {
        return notBlank(teamId) && notBlank(keyId) && notBlank(privateKey);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
