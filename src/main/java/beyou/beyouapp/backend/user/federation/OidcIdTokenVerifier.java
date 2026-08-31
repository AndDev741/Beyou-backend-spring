package beyou.beyouapp.backend.user.federation;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verifies an ID token issued by any configured OIDC provider, against that provider's
 * own published keys.
 *
 * <p>Signature, issuer, audience and expiry — in that order, and all of them. A verifier
 * that checks the signature and forgets the audience accepts a token minted for somebody
 * else's application at the same provider, which is a real and frequently shipped bug.
 *
 * <p><b>The discovery document is not trusted to say who it is.</b> Its {@code issuer}
 * must equal the issuer we configured, or the whole document is discarded: without that
 * check, redirecting our discovery fetch would let an attacker nominate their own JWKS
 * and sign anything they liked.
 *
 * <p>Keys are cached per issuer. A token whose {@code kid} is unknown triggers at most one
 * refetch per {@link #REFETCH_COOLDOWN}, which is what makes key rotation survivable
 * without turning an unknown kid into an unbounded outbound request per login attempt.
 */
@Service
@Slf4j
public class OidcIdTokenVerifier {

    /** Floor between JWKS refetches for one issuer, however many unknown kids arrive. */
    static final Duration REFETCH_COOLDOWN = Duration.ofMinutes(1);

    /** Tolerance for clock drift between us and the issuer. */
    private static final long LEEWAY_SECONDS = 120;

    private final RestTemplate restTemplate;

    public OidcIdTokenVerifier() {
        this(new RestTemplate());
    }

    /** Seam for tests: lets a stub serve discovery and JWKS with no network. */
    OidcIdTokenVerifier(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    private final Cache<String, Map<String, RSAPublicKey>> keysByIssuer =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(6)).maximumSize(32).build();
    private final Cache<String, AtomicLong> lastRefetch =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(6)).maximumSize(32).build();

    /**
     * @param idToken  the raw ID token as the client received it from the provider
     * @param provider the configured expectations this token must satisfy
     * @return the claims, once they are worth reading
     * @throws BusinessException if anything about the token fails to check out
     */
    public FederatedPrincipal verify(String idToken, OidcProviderProperties.Provider provider) {
        DecodedJWT decoded;
        try {
            decoded = JWT.decode(idToken);
        } catch (Exception e) {
            throw invalid("malformed token");
        }

        if (!"RS256".equals(decoded.getAlgorithm())) {
            // Refusing anything else by name, rather than trusting the header to pick the
            // algorithm for us, is what keeps "alg: none" and HS256-with-the-public-key
            // out. The provider publishes RS256 and nothing else.
            throw invalid("unsupported algorithm " + decoded.getAlgorithm());
        }

        RSAPublicKey key = resolveKey(provider.getIssuer(), decoded.getKeyId());

        try {
            JWT.require(Algorithm.RSA256(key, null))
                    .withIssuer(provider.getIssuer())
                    .withAudience(provider.getClientId())
                    .acceptLeeway(LEEWAY_SECONDS)
                    .build()
                    .verify(idToken);
        } catch (JWTVerificationException e) {
            throw invalid(e.getMessage());
        }

        String subject = decoded.getSubject();
        if (subject == null || subject.isBlank()) {
            throw invalid("token carries no subject");
        }

        // azp, when present, names the party the token was actually issued to. A token
        // with our id in a multi-valued aud but somebody else's azp is not ours.
        String azp = decoded.getClaim("azp").asString();
        if (azp != null && !azp.equals(provider.getClientId())) {
            throw invalid("authorized party is not this client");
        }

        return new FederatedPrincipal(
                decoded.getIssuer(),
                subject,
                decoded.getClaim("email").asString(),
                Boolean.TRUE.equals(decoded.getClaim("email_verified").asBoolean()),
                decoded.getClaim("name").asString(),
                decoded.getClaim("picture").asString(),
                null);
    }

    private RSAPublicKey resolveKey(String issuer, String kid) {
        Map<String, RSAPublicKey> keys = keysByIssuer.get(issuer, this::fetchKeys);
        RSAPublicKey key = keys.get(kid);
        if (key != null) {
            return key;
        }

        // Unknown kid: either the provider rotated, or somebody is guessing. Refetch at
        // most once a minute so the second case cannot turn each login into an outbound
        // request, and the first case still heals without a deploy.
        AtomicLong last = lastRefetch.get(issuer, k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        long previous = last.get();
        if (now - previous > REFETCH_COOLDOWN.toMillis() && last.compareAndSet(previous, now)) {
            log.info("Unknown kid {} for issuer {}, refetching JWKS", kid, issuer);
            keys = fetchKeys(issuer);
            keysByIssuer.put(issuer, keys);
            key = keys.get(kid);
        }

        if (key == null) {
            throw invalid("no published key matches the token's kid");
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private Map<String, RSAPublicKey> fetchKeys(String issuer) {
        Map<String, Object> discovery;
        try {
            discovery = restTemplate.getForObject(
                    issuer + "/.well-known/openid-configuration", Map.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorKey.OIDC_TOKEN_INVALID,
                    "Could not reach the identity provider, try again");
        }
        if (discovery == null || !issuer.equals(discovery.get("issuer"))) {
            // See the class comment: a document that does not claim to be this issuer
            // cannot be allowed to nominate the keys we verify with.
            throw invalid("discovery document does not belong to the configured issuer");
        }

        Object jwksUri = discovery.get("jwks_uri");
        if (!(jwksUri instanceof String uri) || !uri.startsWith("https://")) {
            throw invalid("provider published no https jwks_uri");
        }

        Map<String, Object> jwks;
        try {
            jwks = restTemplate.getForObject(uri, Map.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorKey.OIDC_TOKEN_INVALID,
                    "Could not reach the identity provider, try again");
        }
        if (jwks == null || !(jwks.get("keys") instanceof List<?> rawKeys)) {
            throw invalid("provider published no keys");
        }

        Base64.Decoder decoder = Base64.getUrlDecoder();
        java.util.Map<String, RSAPublicKey> result = new java.util.HashMap<>();
        for (Object raw : rawKeys) {
            if (!(raw instanceof Map<?, ?> jwk)) continue;
            if (!"RSA".equals(jwk.get("kty"))) continue;
            Object kid = jwk.get("kid");
            Object n = jwk.get("n");
            Object e = jwk.get("e");
            if (!(kid instanceof String kidStr) || !(n instanceof String nStr) || !(e instanceof String eStr)) {
                continue;
            }
            try {
                RSAPublicKeySpec spec = new RSAPublicKeySpec(
                        new BigInteger(1, decoder.decode(nStr)),
                        new BigInteger(1, decoder.decode(eStr)));
                result.put(kidStr, (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec));
            } catch (Exception ignored) {
                // One unusable key does not condemn the set: providers publish keys for
                // algorithms we do not accept, and the next one may be the right one.
                log.warn("Skipping unusable JWK {} from issuer {}", kidStr, issuer);
            }
        }
        if (result.isEmpty()) {
            throw invalid("provider published no usable RSA keys");
        }
        return result;
    }

    private BusinessException invalid(String detail) {
        log.warn("Rejecting OIDC id token: {}", detail);
        // The caller gets one message for every rejection. Which check failed is useful
        // to us and useful to somebody probing, so it stays in the log.
        return new BusinessException(ErrorKey.OIDC_TOKEN_INVALID, "Invalid identity token");
    }
}
