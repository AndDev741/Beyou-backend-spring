package beyou.beyouapp.backend.user.apple;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.federation.OidcIdTokenVerifier;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Verifies a Sign in with Apple identity token.
 *
 * <p>The cryptography is {@link OidcIdTokenVerifier}'s, unchanged: Apple publishes a normal
 * discovery document and an RS256 JWKS, so signature, issuer, audience, expiry and subject
 * are checked by the same code that checks every other provider, against
 * {@code https://appleid.apple.com/auth/keys}. What is Apple's own is read here:
 *
 * <ul>
 *   <li>the {@code nonce}, which must be the SHA-256 of the raw nonce the app sent us, so a
 *       token lifted off one sign-in cannot open a session for somebody else's request;</li>
 *   <li>{@code email_verified} and {@code is_private_email}, which Apple sends as booleans
 *       for newer accounts and as the strings {@code "true"}/{@code "false"} for older ones.
 *       A plain {@code asBoolean()} reads the string form as null, i.e. unverified.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AppleIdTokenVerifier {

    private final OidcIdTokenVerifier oidcIdTokenVerifier;
    private final AppleSignInProperties properties;

    /**
     * @param identityToken the token exactly as the device received it
     * @param rawNonce      the nonce before hashing, as generated on the device
     * @throws BusinessException {@code OIDC_TOKEN_INVALID} if any check fails
     */
    public AppleIdentity verify(String identityToken, String rawNonce) {
        DecodedJWT token = oidcIdTokenVerifier.verifySignedToken(
                identityToken, properties.getIssuer(), properties.getAudiences());

        String expected = sha256Hex(rawNonce);
        String actual = token.getClaim("nonce").asString();
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejecting Apple identity token: nonce does not match the request");
            throw new BusinessException(ErrorKey.OIDC_TOKEN_INVALID, "Invalid identity token");
        }

        String email = token.getClaim("email").asString();
        return new AppleIdentity(
                token.getSubject(),
                email == null || email.isBlank() ? null : email.trim(),
                flag(token.getClaim("email_verified")),
                flag(token.getClaim("is_private_email")),
                // verifySignedToken already proved one of the accepted audiences is in aud.
                token.getAudience().stream()
                        .filter(properties.getAudiences()::contains)
                        .findFirst()
                        .orElse(token.getAudience().get(0)));
    }

    /** True for {@code true} and for {@code "true"}, false for anything else, missing included. */
    static boolean flag(Claim claim) {
        if (claim == null || claim.isMissing() || claim.isNull()) {
            return false;
        }
        Boolean asBoolean = claim.asBoolean();
        if (asBoolean != null) {
            return asBoolean;
        }
        return "true".equalsIgnoreCase(claim.asString());
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
