package beyou.beyouapp.backend.user.apple;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * The two calls to Apple that need our Sign in with Apple key: exchanging the authorization
 * code from a sign-in for a refresh token, and revoking that token when the account is
 * deleted (App Store guideline 5.1.1(v)).
 *
 * <p>Both authenticate with a "client secret", which for Apple is a short-lived ES256 JWT
 * signed with the .p8 key: {@code iss} = team id, {@code sub} = the client id the token was
 * issued to, {@code aud} = {@code https://appleid.apple.com}, header {@code kid} = key id.
 *
 * <p><b>Never fatal.</b> Neither call can fail a sign-in or a deletion: a missed exchange
 * costs one revocation later, a missed revocation leaves the app listed under the person's
 * Apple ID ("Sign in with Apple" in their settings) until they remove it there. Both are
 * logged, the key and the tokens never are.
 */
@Component
@Slf4j
public class AppleTokenClient {

    /** Apple accepts up to six months; a few minutes is all one request needs. */
    private static final Duration CLIENT_SECRET_TTL = Duration.ofMinutes(5);

    private final AppleSignInProperties properties;
    private final RestTemplate restTemplate;

    @Autowired
    public AppleTokenClient(AppleSignInProperties properties) {
        this(properties, defaultRestTemplate());
    }

    /** Seam for tests: a stubbed RestTemplate stands in for Apple. */
    AppleTokenClient(AppleSignInProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate defaultRestTemplate() {
        // Bounded, because the exchange runs inside a sign-in request and Apple being slow
        // must not hold the person on a spinner.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        return new RestTemplate(factory);
    }

    public boolean isConfigured() {
        return properties.isRevocationConfigured();
    }

    /**
     * @return Apple's refresh token for this sign-in, or empty when the key is not configured,
     *         no code was sent, or Apple refused (an authorization code is single-use and
     *         expires after five minutes)
     */
    public Optional<String> exchangeAuthorizationCode(String authorizationCode, String clientId) {
        if (!isConfigured() || authorizationCode == null || authorizationCode.isBlank()) {
            return Optional.empty();
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret(clientId));
            form.add("code", authorizationCode);
            form.add("grant_type", "authorization_code");

            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.postForObject(
                    properties.getApiBaseUrl() + "/auth/token", formEntity(form), Map.class);
            Object refreshToken = body == null ? null : body.get("refresh_token");
            if (refreshToken instanceof String token && !token.isBlank()) {
                return Optional.of(token);
            }
            log.warn("Apple token exchange answered without a refresh token");
        } catch (Exception e) {
            log.warn("Apple authorization code exchange failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** @return whether Apple confirmed the revocation */
    public boolean revokeRefreshToken(String refreshToken, String clientId) {
        if (!isConfigured()) {
            return false;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret(clientId));
            form.add("token", refreshToken);
            form.add("token_type_hint", "refresh_token");

            ResponseEntity<String> response = restTemplate.postForEntity(
                    properties.getApiBaseUrl() + "/auth/revoke", formEntity(form), String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Apple token revocation failed: {}", e.getMessage());
            return false;
        }
    }

    /** The ES256 client secret Apple's token endpoints authenticate us with. */
    String clientSecret(String clientId) {
        Instant now = Instant.now();
        return JWT.create()
                .withKeyId(properties.getKeyId().trim())
                .withIssuer(properties.getTeamId().trim())
                .withSubject(clientId)
                .withAudience("https://appleid.apple.com")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plus(CLIENT_SECRET_TTL)))
                .sign(Algorithm.ECDSA256(null, privateKey()));
    }

    private ECPrivateKey privateKey() {
        // An env var is one line, so accept "\n" written out as well as real newlines, and
        // the PEM armour with or without its header lines.
        String pem = properties.getPrivateKey().replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(pem);
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            // The message only; the key must never reach a log line.
            throw new IllegalStateException("APPLE_PRIVATE_KEY is not a valid .p8 (PKCS#8 EC) key");
        }
    }

    private static HttpEntity<MultiValueMap<String, String>> formEntity(MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return new HttpEntity<>(form, headers);
    }
}
