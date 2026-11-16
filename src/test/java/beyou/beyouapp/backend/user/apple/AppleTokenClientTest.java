package beyou.beyouapp.backend.user.apple;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * The calls that need our Sign in with Apple key. No network: Apple is a mocked
 * RestTemplate and the .p8 is an EC P-256 key generated per run.
 */
@DisplayName("AppleTokenClient")
class AppleTokenClientTest {

    private static final KeyPair KEY = ecKey();

    private AppleSignInProperties properties;
    private RestTemplate http;
    private AppleTokenClient client;

    @BeforeEach
    void setUp() {
        properties = new AppleSignInProperties();
        properties.setTeamId("6FYT6632J8");
        properties.setKeyId("ABC123DEFG");
        // Written the way an env var carries it: one line, "\n" spelled out.
        properties.setPrivateKey("-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(KEY.getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----");
        http = mock(RestTemplate.class);
        client = new AppleTokenClient(properties, http);
    }

    @Test
    @DisplayName("the client secret is an ES256 JWT Apple can check: kid, iss=team, sub=client, aud=Apple")
    void clientSecretHasApplesShape() {
        String secret = client.clientSecret("com.beyou.mobile");

        DecodedJWT verified = JWT.require(Algorithm.ECDSA256((ECPublicKey) KEY.getPublic(), null))
                .withIssuer("6FYT6632J8")
                .withSubject("com.beyou.mobile")
                .withAudience("https://appleid.apple.com")
                .build()
                .verify(secret);
        assertThat(verified.getKeyId()).isEqualTo("ABC123DEFG");
        assertThat(verified.getExpiresAtAsInstant()).isAfter(verified.getIssuedAtAsInstant());
    }

    @Test
    @DisplayName("an authorization code is exchanged for Apple's refresh token")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void exchangesTheCode() {
        when(http.postForObject(eq("https://appleid.apple.com/auth/token"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("refresh_token", "apple-refresh", "id_token", "ignored"));

        assertThat(client.exchangeAuthorizationCode("code-1", "com.beyou.mobile")).contains("apple-refresh");

        ArgumentCaptor<HttpEntity> sent = ArgumentCaptor.forClass(HttpEntity.class);
        verify(http).postForObject(eq("https://appleid.apple.com/auth/token"), sent.capture(), eq(Map.class));
        MultiValueMap<String, String> form = (MultiValueMap<String, String>) sent.getValue().getBody();
        assertThat(form.getFirst("grant_type")).isEqualTo("authorization_code");
        assertThat(form.getFirst("code")).isEqualTo("code-1");
        assertThat(form.getFirst("client_id")).isEqualTo("com.beyou.mobile");
        assertThat(form.getFirst("client_secret")).isNotBlank();
    }

    @Test
    @DisplayName("Apple refusing the code costs the refresh token, never an exception")
    void aRefusedCodeIsNotFatal() {
        when(http.postForObject(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThat(client.exchangeAuthorizationCode("used-code", "com.beyou.mobile")).isEmpty();
    }

    @Test
    @DisplayName("without the key nothing is sent to Apple at all")
    void unconfiguredSendsNothing() {
        properties.setPrivateKey(null);

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.exchangeAuthorizationCode("code-1", "com.beyou.mobile")).isEmpty();
        assertThat(client.revokeRefreshToken("apple-refresh", "com.beyou.mobile")).isFalse();
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("revoking posts the refresh token with its hint and reports Apple's 200")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void revokes() {
        when(http.postForEntity(eq("https://appleid.apple.com/auth/revoke"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(""));

        assertThat(client.revokeRefreshToken("apple-refresh", "com.beyou.mobile")).isTrue();

        ArgumentCaptor<HttpEntity> sent = ArgumentCaptor.forClass(HttpEntity.class);
        verify(http).postForEntity(eq("https://appleid.apple.com/auth/revoke"), sent.capture(), eq(String.class));
        MultiValueMap<String, String> form = (MultiValueMap<String, String>) sent.getValue().getBody();
        assertThat(form.getFirst("token")).isEqualTo("apple-refresh");
        assertThat(form.getFirst("token_type_hint")).isEqualTo("refresh_token");
    }

    @Test
    @DisplayName("a revocation Apple refuses reports false instead of throwing")
    void aRefusedRevocationIsNotFatal() {
        when(http.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThat(client.revokeRefreshToken("apple-refresh", "com.beyou.mobile")).isFalse();
    }

    private static KeyPair ecKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
