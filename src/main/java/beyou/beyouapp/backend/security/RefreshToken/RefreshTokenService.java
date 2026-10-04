package beyou.beyouapp.backend.security.RefreshToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.exceptions.security.RefreshTokenDontMatchRaw;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenExpiredException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenNotFoundException;
import beyou.beyouapp.backend.monitoring.UserActivityTracker;
import beyou.beyouapp.backend.security.ClientType;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final UserActivityTracker userActivityTracker;
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Base64.Encoder base64Encoder = Base64.getUrlEncoder().withoutPadding();

    /**
     * Marks a token_hash written as SHA-256, as opposed to the BCrypt rows written before it.
     *
     * <p>Refresh tokens are 256 bits from {@link SecureRandom}, not something a person chose.
     * BCrypt's cost factor exists to slow down guessing a low-entropy secret; there is nothing
     * to guess here, so it only bought latency: two BCrypt(12) operations per refresh (verify
     * the old token, hash the new one) held {@code /auth/refresh} at ~850 ms p50 on the prod
     * box. A plain SHA-256 is the standard choice for high-entropy tokens.
     *
     * <p>Passwords stay on the shared {@link PasswordEncoder}. Do not "unify" the two.
     */
    static final String SHA256_PREFIX = "sha256:";

    public String createRefreshToken(User user) {
        var token = new RefreshToken();
        var opaqueToken = generateOpaqueToken();
        token.setUser(user);
        token.setCreatedAt(Timestamp.from(Instant.now()));
        token.setExpiresAt(Timestamp.from(Instant.now().plus(Duration.ofDays(15))));
        token.setTokenHash(hashToken(opaqueToken));

        repository.save(token);

        // The one place every session-issuing path (password login, Google web, Google
        // mobile, refresh) already converges, so last_login_at cannot miss a path.
        userActivityTracker.recordLogin(user.getId());

        return token.getId() + "." + opaqueToken; //id.token
    }

    @Transactional
    public Optional<String> refreshAccessToken(HttpServletRequest request, HttpServletResponse response){
        String cookieValue = recoverToken(request, true);

        String[] parts = cookieValue.split("\\.");
        if(parts.length != 2){
            throw new RefreshTokenNotFoundException("Refresh token malformed");
        }
        UUID tokenId = UUID.fromString(parts[0]);
        String rawToken = parts[1];

        RefreshToken refreshToken = repository.findById(tokenId)
        .orElseThrow(() -> new RefreshTokenNotFoundException("Refresh token not found in database"));

        isNotMatchingOrExpired(refreshToken, rawToken, true);

        refreshToken.setRevokedAt(Timestamp.from(Instant.now()));
        repository.save(refreshToken);

        boolean mobile = ClientType.isMobile(request);
        String newToken = tokenService.generateJwtToken(refreshToken.getUser());
        String newRefreshToken = createRefreshToken(refreshToken.getUser());
        tokenService.addJwtTokenToResponse(response, newToken, newRefreshToken, mobile);
        return mobile ? Optional.of(newRefreshToken) : Optional.empty();
    }

    public void revokeRefreshToken(HttpServletRequest request, HttpServletResponse response){
        clearRefreshTokenCookie(response);
        String cookieValue = recoverToken(request, false);
        if(cookieValue == null){
            return;
        }
        
        String[] parts = cookieValue.split("\\.");
        if(parts.length != 2){
            return;
        }

        UUID tokenId = Optional.ofNullable(parts[0])
                .map(idStr -> {
                    try {
                        return UUID.fromString(idStr);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .orElse(null);
        if(tokenId == null) return;

        String rawToken = parts[1];

        Optional<RefreshToken> refreshToken = repository.findById(tokenId);

        if(refreshToken.isEmpty()) return;

        boolean isNotMatchingOrExpired = isNotMatchingOrExpired(refreshToken.get(), rawToken, false);
        if(isNotMatchingOrExpired) return;

        refreshToken.get().setRevokedAt(Timestamp.from(Instant.now()));
        repository.save(refreshToken.get());
    }

    @Transactional
    public void revokeAllForUser(User user){
        List<RefreshToken> tokens = repository.findAllByUserId(user.getId());
        if(tokens.isEmpty()) return;
        Timestamp now = Timestamp.from(Instant.now());
        for(RefreshToken token : tokens){
            token.setRevokedAt(now);
        }
        repository.saveAll(tokens);
    }

    /**
     * R21 — deletes this account's refresh tokens, as opposed to
     * {@link #revokeAllForUser(User)}, which only stamps them revoked.
     *
     * Revoking is right when the account survives and the sessions must not.
     * This is for the account NOT surviving: the rows carry a non-cascading
     * foreign key to {@code users}, so they have to be gone before the user row
     * can be, and a revoked row still references it.
     *
     * @return how many token rows were removed
     */
    @Transactional
    public int deleteAllForUser(UUID userId){
        return repository.deleteAllByUserId(userId);
    }

    public boolean isTokenExpired(RefreshToken token) {
        return token.getExpiresAt().before(Timestamp.from(Instant.now()));
    }

    public String recoverToken(HttpServletRequest request, boolean throwIfNotFound){
        Optional<String> cookieToken = Optional.ofNullable(request.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies)
                        .filter(c -> "refreshToken".equals(c.getName()))
                        .findFirst())
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank());

        String headerToken = request.getHeader("X-Refresh-Token");
        String token = cookieToken.orElse((headerToken != null && !headerToken.isBlank()) ? headerToken : null);

        if (throwIfNotFound && token == null) {
            throw new RefreshTokenNotFoundException("Refresh token not found in cookies or headers");
        }
        return token;
    }

    private boolean isNotMatchingOrExpired(RefreshToken refreshToken, String rawToken, boolean throwIfExpired){
        if(!tokenMatches(rawToken, refreshToken.getTokenHash())) {
            if(!throwIfExpired) return true;
            throw new RefreshTokenDontMatchRaw("Refresh token don't match with stored in database");
        }

        if(isTokenExpired(refreshToken) || refreshToken.getRevokedAt() != null){
            if(!throwIfExpired) return true;
            throw new RefreshTokenExpiredException("Refresh token expired or already revoked");
        }
        return false;
    }

    static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return SHA256_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to ship SHA-256.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Rows written before the switch hold a BCrypt hash, and they live up to 15 days. They
     * are still verified with BCrypt so nobody is logged out by the deploy; each one is
     * replaced by a SHA-256 row on its next rotation, so this branch empties itself out.
     */
    private boolean tokenMatches(String rawToken, String storedHash) {
        if (storedHash == null) return false;
        if (storedHash.startsWith(SHA256_PREFIX)) {
            return MessageDigest.isEqual(
                    hashToken(rawToken).getBytes(StandardCharsets.UTF_8),
                    storedHash.getBytes(StandardCharsets.UTF_8));
        }
        return passwordEncoder.matches(rawToken, storedHash);
    }

    private static String generateOpaqueToken() {
        byte[] randomBytes = new byte[32]; // Aproximaly 43 chars
        secureRandom.nextBytes(randomBytes);
        return base64Encoder.encodeToString(randomBytes);
    }

    private void clearRefreshTokenCookie(HttpServletResponse response){
        ResponseCookie cookie = tokenService.buildRefreshCookie("", Duration.ZERO);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

}
