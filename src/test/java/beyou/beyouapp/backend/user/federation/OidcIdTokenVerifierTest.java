package beyou.beyouapp.backend.user.federation;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Each check the verifier makes, attacked one at a time with a token that passes every
 * other check. A token that fails two things at once would let a broken check hide behind
 * a working one, so every rejection case here starts from a token the verifier accepts
 * ({@link #acceptsAWellFormedToken()}) and changes exactly one thing.
 *
 * <p>No network: the discovery document and the JWKS come from a mocked RestTemplate, and
 * the tokens are signed with an RSA key generated per test class.
 */
class OidcIdTokenVerifierTest {

    private static final String ISSUER = "https://id.example.test";
    private static final String DISCOVERY = ISSUER + "/.well-known/openid-configuration";
    private static final String JWKS = ISSUER + "/jwks";
    private static final String CLIENT_ID = "beyou-web";

    private static final KeyPair KEY = rsa();
    private static final KeyPair OTHER_KEY = rsa();

    private RestTemplate http;
    private OidcIdTokenVerifier verifier;
    private OidcProviderProperties.Provider provider;

    @BeforeEach
    void setUp() {
        http = mock(RestTemplate.class);
        verifier = new OidcIdTokenVerifier(http);
        provider = new OidcProviderProperties.Provider();
        provider.setIssuer(ISSUER);
        provider.setClientId(CLIENT_ID);
        serveDiscovery(ISSUER, JWKS);
        serveKeys(jwk("k1", KEY));
    }

    @Test
    @DisplayName("a token signed by the published key, for us, unexpired, is accepted")
    void acceptsAWellFormedToken() {
        FederatedPrincipal principal = verifier.verify(sign(validToken()), provider);

        assertEquals(ISSUER, principal.issuer());
        assertEquals("subject-1", principal.subject());
        assertEquals("person@example.test", principal.email());
        assertTrue(principal.emailVerified());
    }

    @Test
    @DisplayName("alg none is refused by name, before any key is looked up")
    void refusesAlgNone() {
        String token = validToken().sign(Algorithm.none());

        assertInvalid(token);
    }

    @Test
    @DisplayName("HS256 signed with the public key as the HMAC secret is refused")
    void refusesHs256KeyConfusion() {
        // The classic confusion attack: the public key is public, so an attacker can use its
        // bytes as an HMAC secret. A verifier that let the header choose the algorithm would
        // check that MAC with the same bytes and accept it.
        byte[] publicKeyBytes = KEY.getPublic().getEncoded();
        String token = validToken().sign(Algorithm.HMAC256(publicKeyBytes));

        assertInvalid(token);
    }

    @Test
    @DisplayName("a token signed by a key the issuer never published is refused")
    void refusesAForeignSignature() {
        String token = validToken().sign(Algorithm.RSA256(
                (RSAPublicKey) OTHER_KEY.getPublic(), (RSAPrivateKey) OTHER_KEY.getPrivate()));

        assertInvalid(token);
    }

    @Test
    @DisplayName("a token from another issuer is refused even when the signature checks out")
    void refusesTheWrongIssuer() {
        assertInvalid(sign(validToken().withIssuer("https://elsewhere.example.test")));
    }

    @Test
    @DisplayName("a token minted for another client at the same provider is refused")
    void refusesTheWrongAudience() {
        assertInvalid(sign(validToken().withAudience("someone-elses-app")));
    }

    @Test
    @DisplayName("our id in aud is not enough when azp names somebody else")
    void refusesAForeignAuthorizedParty() {
        assertInvalid(sign(validToken()
                .withAudience(CLIENT_ID, "someone-elses-app")
                .withClaim("azp", "someone-elses-app")));
    }

    @Test
    @DisplayName("an expired token is refused once it is past the clock leeway")
    void refusesAnExpiredToken() {
        Instant tenMinutesAgo = Instant.now().minusSeconds(600);
        assertInvalid(sign(validToken()
                .withIssuedAt(tenMinutesAgo.minusSeconds(3600))
                .withExpiresAt(tenMinutesAgo)));
    }

    @Test
    @DisplayName("a token with no subject is refused")
    void refusesAMissingSubject() {
        JWTCreator.Builder builder = JWT.create()
                .withKeyId("k1")
                .withIssuer(ISSUER)
                .withAudience(CLIENT_ID)
                .withExpiresAt(Instant.now().plusSeconds(600));

        assertInvalid(sign(builder));
    }

    @Test
    @DisplayName("a rotated key is picked up by one refetch, without a deploy")
    void picksUpARotatedKey() {
        verifier.verify(sign(validToken()), provider);
        serveKeys(jwk("k1", KEY), jwk("k2", OTHER_KEY));

        String rotated = validToken().withKeyId("k2").sign(Algorithm.RSA256(
                (RSAPublicKey) OTHER_KEY.getPublic(), (RSAPrivateKey) OTHER_KEY.getPrivate()));

        assertEquals("subject-1", verifier.verify(rotated, provider).subject());
        verify(http, times(2)).getForObject(eq(JWKS), eq(Map.class));
    }

    @Test
    @DisplayName("unknown kids refetch the JWKS at most once per cooldown")
    void boundsRefetchesForUnknownKids() {
        verifier.verify(sign(validToken()), provider);

        for (int i = 0; i < 5; i++) {
            assertInvalid(sign(validToken().withKeyId("guess-" + i)));
        }

        // One fetch to fill the cache, one refetch for the first unknown kid, and nothing
        // for the other four: a stream of made-up kids must not become a stream of
        // outbound requests.
        verify(http, times(2)).getForObject(eq(JWKS), eq(Map.class));
    }

    @Test
    @DisplayName("a discovery document claiming another issuer cannot nominate the keys")
    void refusesAForgedDiscoveryIssuer() {
        // Somebody who can redirect our discovery fetch serves a document naming their own
        // issuer and JWKS. Their key signs a token that is otherwise perfect for us.
        serveDiscovery("https://attacker.example.test", "https://attacker.example.test/jwks");
        when(http.getForObject(eq("https://attacker.example.test/jwks"), eq(Map.class)))
                .thenReturn(Map.of("keys", List.of(jwk("k1", OTHER_KEY))));

        String token = validToken().sign(Algorithm.RSA256(
                (RSAPublicKey) OTHER_KEY.getPublic(), (RSAPrivateKey) OTHER_KEY.getPrivate()));

        assertInvalid(token);
        verify(http, times(0)).getForObject(eq("https://attacker.example.test/jwks"), eq(Map.class));
    }

    @Test
    @DisplayName("a JWKS served over plain http is never fetched")
    void refusesAnInsecureJwksUri() {
        serveDiscovery(ISSUER, "http://id.example.test/jwks");

        assertInvalid(sign(validToken()));
        verify(http, times(0)).getForObject(eq("http://id.example.test/jwks"), eq(Map.class));
    }

    @Test
    @DisplayName("an unreachable provider is an invalid token, not a 500")
    void unreachableProviderIsAnInvalidToken() {
        when(http.getForObject(eq(DISCOVERY), eq(Map.class))).thenThrow(new RuntimeException("timeout"));

        assertInvalid(sign(validToken()));
    }

    private void assertInvalid(String token) {
        BusinessException e = assertThrows(BusinessException.class, () -> verifier.verify(token, provider));
        assertEquals(ErrorKey.OIDC_TOKEN_INVALID, e.getErrorKey());
    }

    private static JWTCreator.Builder validToken() {
        Instant now = Instant.now();
        return JWT.create()
                .withKeyId("k1")
                .withIssuer(ISSUER)
                .withSubject("subject-1")
                .withAudience(CLIENT_ID)
                .withIssuedAt(now)
                .withExpiresAt(now.plusSeconds(600))
                .withClaim("email", "person@example.test")
                .withClaim("email_verified", true);
    }

    private static String sign(JWTCreator.Builder builder) {
        return builder.sign(Algorithm.RSA256((RSAPublicKey) KEY.getPublic(), (RSAPrivateKey) KEY.getPrivate()));
    }

    private void serveDiscovery(String claimedIssuer, String jwksUri) {
        when(http.getForObject(eq(DISCOVERY), eq(Map.class)))
                .thenReturn(Map.of("issuer", claimedIssuer, "jwks_uri", jwksUri));
    }

    @SafeVarargs
    private void serveKeys(Map<String, Object>... keys) {
        when(http.getForObject(eq(JWKS), eq(Map.class)))
                .thenReturn(Map.of("keys", new ArrayList<>(List.of(keys))));
    }

    private static Map<String, Object> jwk(String kid, KeyPair pair) {
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return Map.of(
                "kty", "RSA",
                "kid", kid,
                "alg", "RS256",
                "n", b64.encodeToString(unsigned(pub.getModulus())),
                "e", b64.encodeToString(unsigned(pub.getPublicExponent())));
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
