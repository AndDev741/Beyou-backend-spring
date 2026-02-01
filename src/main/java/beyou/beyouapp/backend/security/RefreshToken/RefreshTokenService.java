package beyou.beyouapp.backend.security.RefreshToken;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.exceptions.security.RefreshTokenDontMatchRaw;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenExpiredException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenNotFoundException;
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
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Base64.Encoder base64Encoder = Base64.getUrlEncoder();

    public String createRefreshToken(User user) {
        var token = new RefreshToken();
        var opaqueToken = generateOpaqueToken();
        token.setUser(user);
        token.setCreatedAt(Timestamp.from(Instant.now()));
        token.setExpiresAt(Timestamp.from(Instant.now().plus(Duration.ofDays(15))));
        token.setTokenHash(passwordEncoder.encode(opaqueToken));

        repository.save(token);

        return token.getId() + "." + opaqueToken; //id.token
    }

    @Transactional
    public void refreshAccessToken(HttpServletRequest request, HttpServletResponse response){
        String cookieValue = recoverToken(request);

        String[] parts = cookieValue.split("\\.");
        if(parts.length != 2){
            throw new RefreshTokenNotFoundException("Refresh token malformed");
        }
        UUID tokenId = UUID.fromString(parts[0]);
        String rawToken = parts[1];

        RefreshToken refreshToken = repository.findById(tokenId)
        .orElseThrow(() -> new RefreshTokenNotFoundException("Refresh token not found in database"));

        if(!passwordEncoder.matches(rawToken, refreshToken.getTokenHash())) {
            throw new RefreshTokenDontMatchRaw("Refresh token don't match with stored in database");
        }

        if(isTokenExpired(refreshToken) || refreshToken.getRevokedAt() != null){
            throw new RefreshTokenExpiredException("Refresh token expired or already revoked");
        }

        refreshToken.setRevokedAt(Timestamp.from(Instant.now()));
        repository.save(refreshToken);

        String newToken = tokenService.generateJwtToken(refreshToken.getUser());
        String newRefreshToken = createRefreshToken(refreshToken.getUser());
        tokenService.addJwtTokenToResponse(response, newToken, newRefreshToken);

    }

    public boolean isTokenExpired(RefreshToken token) {
        return token.getExpiresAt().before(Timestamp.from(Instant.now()));
    }

    private static String generateOpaqueToken() {
        byte[] randomBytes = new byte[32]; // Aproximaly 43 chars
        secureRandom.nextBytes(randomBytes);
        return base64Encoder.encodeToString(randomBytes);
    }
    
    public String recoverToken(HttpServletRequest request){
        return Optional.ofNullable(request.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies)
                        .filter(cookie -> "refreshToken".equals(cookie.getName()))
                        .findFirst())
                .map(Cookie::getValue)
                .orElseThrow(() -> new RefreshTokenNotFoundException("Refresh token not found"));
    }
}
