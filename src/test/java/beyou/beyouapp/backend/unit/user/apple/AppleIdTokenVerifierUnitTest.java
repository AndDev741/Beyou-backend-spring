package beyou.beyouapp.backend.unit.user.apple;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.apple.AppleIdTokenVerifier;
import beyou.beyouapp.backend.user.apple.AppleIdentity;
import beyou.beyouapp.backend.user.apple.AppleSignInProperties;
import beyou.beyouapp.backend.user.federation.OidcIdTokenVerifier;

/**
 * What the Apple verifier adds on top of the generic OIDC checks, which have their own
 * attack-by-attack test in OidcIdTokenVerifierTest: the nonce, and Apple's habit of sending
 * its boolean claims as strings. The signature check is stubbed to "passed" here, so every
 * case below is about a token that is genuinely Apple's.
 */
@DisplayName("AppleIdTokenVerifier")
class AppleIdTokenVerifierUnitTest {

    private static final String RAW_NONCE = "raw-nonce-from-the-device";

    private OidcIdTokenVerifier oidc;
    private AppleSignInProperties properties;
    private AppleIdTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        oidc = mock(OidcIdTokenVerifier.class);
        properties = new AppleSignInProperties();
        verifier = new AppleIdTokenVerifier(oidc, properties);
    }

    /** A token whose signature the stubbed OIDC layer accepts as Apple's. */
    private String accepted(JWTCreator.Builder builder) {
        String token = builder.sign(Algorithm.HMAC256("only-the-stub-reads-this"));
        when(oidc.verifySignedToken(eq(token), eq("https://appleid.apple.com"), anyCollection()))
                .thenReturn(JWT.decode(token));
        return token;
    }

    private static JWTCreator.Builder appleToken() {
        return JWT.create()
                .withIssuer("https://appleid.apple.com")
                .withAudience("com.beyou.mobile")
                .withSubject("001234.abcdef.0987")
                .withClaim("nonce", sha256(RAW_NONCE))
                .withClaim("email", "person@example.test")
                .withClaim("email_verified", true);
    }

    @Test
    @DisplayName("a token whose nonce is the SHA-256 of the raw nonce is accepted")
    void acceptsTheMatchingNonce() {
        AppleIdentity identity = verifier.verify(accepted(appleToken()), RAW_NONCE);

        assertThat(identity.subject()).isEqualTo("001234.abcdef.0987");
        assertThat(identity.email()).isEqualTo("person@example.test");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.privateEmail()).isFalse();
        assertThat(identity.audience()).isEqualTo("com.beyou.mobile");
    }

    @Test
    @DisplayName("a token minted for another sign-in's nonce is refused")
    void refusesAForeignNonce() {
        String token = accepted(appleToken().withClaim("nonce", sha256("somebody-elses-nonce")));

        assertThatThrownBy(() -> verifier.verify(token, RAW_NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.OIDC_TOKEN_INVALID);
    }

    @Test
    @DisplayName("the raw nonce in the token, instead of its hash, is refused")
    void refusesTheUnhashedNonce() {
        String token = accepted(appleToken().withClaim("nonce", RAW_NONCE));

        assertThatThrownBy(() -> verifier.verify(token, RAW_NONCE)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a token with no nonce at all is refused")
    void refusesAMissingNonce() {
        JWTCreator.Builder noNonce = JWT.create()
                .withIssuer("https://appleid.apple.com")
                .withAudience("com.beyou.mobile")
                .withSubject("001234.abcdef.0987");

        String token = accepted(noNonce);

        assertThatThrownBy(() -> verifier.verify(token, RAW_NONCE)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("email_verified and is_private_email sent as strings still read as true")
    void readsStringFlags() {
        AppleIdentity identity = verifier.verify(accepted(appleToken()
                .withClaim("email", "x7k2abcd9q@privaterelay.appleid.com")
                .withClaim("email_verified", "true")
                .withClaim("is_private_email", "true")), RAW_NONCE);

        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.privateEmail()).isTrue();
    }

    @Test
    @DisplayName("email_verified \"false\" reads as false, not as a present claim")
    void readsAFalseString() {
        AppleIdentity identity = verifier.verify(accepted(appleToken()
                .withClaim("email_verified", "false")), RAW_NONCE);

        assertThat(identity.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("the audience reported is the accepted one, not whatever else rides in aud")
    void reportsTheAcceptedAudience() {
        properties.setAudiences(List.of("com.beyou.web", "com.beyou.mobile"));

        AppleIdentity identity = verifier.verify(accepted(appleToken()
                .withAudience("something-else", "com.beyou.mobile")), RAW_NONCE);

        assertThat(identity.audience()).isEqualTo("com.beyou.mobile");
    }

    @Test
    @DisplayName("the generic checks' refusal is passed through untouched")
    void propagatesTheGenericRefusal() {
        when(oidc.verifySignedToken(anyString(), anyString(), anyCollection()))
                .thenThrow(new BusinessException(ErrorKey.OIDC_TOKEN_INVALID, "Invalid identity token"));

        assertThatThrownBy(() -> verifier.verify("forged", RAW_NONCE))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid identity token");
    }

    @Test
    @DisplayName("an APPLE_AUDIENCES set but empty falls back to the bundle id")
    void emptyAudiencesFallBackToTheBundleId() {
        properties.setAudiences(List.of(" ", ""));

        assertThat(properties.getAudiences()).containsExactly("com.beyou.mobile");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
