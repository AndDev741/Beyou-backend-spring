package beyou.beyouapp.backend.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs and checks the profile-photo URLs.
 *
 * <p>{@code GET /user/photo/{userId}} used to be open to anybody who could guess a
 * UUID, which made every uploaded face readable by an unauthenticated caller. It
 * cannot simply move behind the JWT: the URL is consumed by an {@code <img src>} on
 * the web and an {@code <Image uri>} on the phone, and neither can carry an
 * Authorization header.
 *
 * <p>So the URL carries its own proof. {@link UserMapper} mints one when it answers
 * {@code GET /user} — the only response that ever contains it, and one only its owner
 * can read — and the controller refuses anything without a matching signature. The
 * secret never leaves the server, so a signature cannot be produced for a user id
 * that was merely guessed.
 *
 * <p>The key is derived from {@code TOKEN_SECRET} rather than configured separately.
 * One fewer secret to deploy, and a derived key still keeps the two uses apart: a
 * photo signature is useless as a JWT and the reverse.
 */
@Component
public class PhotoUrlSigner {

    private static final String HMAC = "HmacSHA256";

    /** Domain separation. Changing this string invalidates every URL already minted. */
    private static final byte[] KEY_LABEL = "beyou-photo-url-v1".getBytes(StandardCharsets.UTF_8);

    private final byte[] key;
    private final Duration ttl;

    /**
     * @param tokenSecret the JWT signing secret; only the derived key is retained
     * @param ttlMinutes  how long a minted URL stays loadable. Twelve hours by
     *                    default: long enough that a tab left open overnight still
     *                    renders an avatar, short enough that a URL leaked through
     *                    a browser history or a proxy log stops working the same day.
     */
    public PhotoUrlSigner(@Value("${api.security.token.secret}") String tokenSecret,
                          @Value("${app.photo-url-ttl-minutes:720}") long ttlMinutes) {
        this.key = hmac(tokenSecret.getBytes(StandardCharsets.UTF_8), KEY_LABEL);
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * The query string a client appends to {@code /user/photo/{userId}}, version
     * included so the image cache still busts exactly when the photo changes.
     */
    public String signedQuery(UUID userId, long photoVersion) {
        long expiresAt = Instant.now().plus(ttl).getEpochSecond();
        return "?v=" + photoVersion
                + "&exp=" + expiresAt
                + "&sig=" + sign(userId, expiresAt);
    }

    /** True when {@code sig} was minted here for this user and has not expired yet. */
    public boolean isValid(UUID userId, String exp, String sig) {
        if (exp == null || sig == null) {
            return false;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(exp);
        } catch (NumberFormatException malformed) {
            return false;
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            return false;
        }
        // Constant-time: a byte-by-byte comparison that returns early leaks how much
        // of a guessed signature was right, which is enough to walk one out.
        return MessageDigest.isEqual(
                sign(userId, expiresAt).getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(UUID userId, long expiresAt) {
        byte[] payload = (userId + "|" + expiresAt).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, payload));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(message);
        } catch (Exception e) {
            // HmacSHA256 is mandatory on every JRE and the key is never empty here,
            // so reaching this means the platform is broken, not the request.
            throw new IllegalStateException("Could not sign the photo URL", e);
        }
    }
}
