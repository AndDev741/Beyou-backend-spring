package beyou.beyouapp.backend.unit.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import beyou.beyouapp.backend.exceptions.security.RefreshTokenDontMatchRaw;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenExpiredException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenNotFoundException;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshToken;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@ExtendWith(MockitoExtension.class)
public class RefreshTokenServiceUnitTest {

    @Mock
    private RefreshTokenRepository repository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    RefreshToken refreshToken = new RefreshToken();
    UUID userId = UUID.randomUUID();
    UUID tokenId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        refreshToken.setId(tokenId);
        refreshToken.setTokenHash("hashedToken");
        refreshToken.setExpiresAt(new Timestamp(System.currentTimeMillis() + 100000)); // expires in the future
    }

    @Nested
    public class happyPath {
        @Test
        void testCreateRefreshToken() {
            // Given
            var user = new User();
            user.setId(userId);
            String encodedToken = "encodedToken";

            when(passwordEncoder.encode(anyString())).thenReturn(encodedToken);
            // When
            String result = refreshTokenService.createRefreshToken(user);

            // Then
            assertNotNull(result);
            String[] parts = result.split("\\.");
            assertEquals(2, parts.length);
        }

        @Test
        void testRefreshAccessToken() {
            // Given
            String rawToken = "rawToken";
            String cookieValue = tokenId.toString() + "." + rawToken;

            when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("refreshToken", cookieValue) });
            when(repository.findById(tokenId)).thenReturn(Optional.of(refreshToken));
            when(passwordEncoder.matches(rawToken, refreshToken.getTokenHash())).thenReturn(true);

            // Then
            assertDoesNotThrow(() -> refreshTokenService.refreshAccessToken(request, response));
            // Here the token is already revoked because of the last call
            assertThrows(RefreshTokenExpiredException.class,
                    () -> refreshTokenService.refreshAccessToken(request, response));
            verify(repository, times(2)).findById(tokenId);
            verify(passwordEncoder, times(2)).matches(rawToken, refreshToken.getTokenHash());
        }

        @Test
        void shouldReturnTrueIfTokenIsExpired() {
            // Given
            refreshToken.setExpiresAt(new Timestamp(System.currentTimeMillis() - 100000)); // expired in the past

            // When
            boolean isExpired = refreshTokenService.isTokenExpired(refreshToken);

            // Then
            assertEquals(true, isExpired);
        }

        @Test
        void testRevokeRefreshToken() {
            // Given
            String cookieValue = tokenId.toString() + ".rawToken";

            when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("refreshToken", cookieValue) });
            when(repository.findById(tokenId)).thenReturn(Optional.of(refreshToken));
            when(passwordEncoder.matches("rawToken", refreshToken.getTokenHash())).thenReturn(true);

            // When
            refreshTokenService.revokeRefreshToken(request, response);

            // Then
            verify(repository).save(refreshToken);
            verify(response)
                    .addCookie(argThat(cookie -> cookie.getName().equals("refreshToken") && cookie.getMaxAge() == 0));
        }

    }

    @Nested
    public class exceptions {

        @Test
        void testRefreshAccessTokenWithMalformedToken() {
            // Given
            String cookieValue = "malformedToken";

            when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("refreshToken", cookieValue) });

            // Then
            assertThrows(RefreshTokenNotFoundException.class,
                    () -> refreshTokenService.refreshAccessToken(request, response));
        }

        @Test
        void testRefreshAccessTokenWithNonExistingToken() {
            // Given
            String rawToken = "rawToken";
            String cookieValue = tokenId.toString() + "." + rawToken;

            when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("refreshToken", cookieValue) });
            when(repository.findById(tokenId)).thenReturn(Optional.empty());

            // Then
            assertThrows(RefreshTokenNotFoundException.class,
                    () -> refreshTokenService.refreshAccessToken(request, response));
        }

        @Test
        void testRefreshAccessTokenWithNonMatchingRawToken() {
            // Given
            String rawToken = "rawToken";
            String cookieValue = tokenId.toString() + "." + rawToken;

            when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("refreshToken", cookieValue) });
            when(repository.findById(tokenId)).thenReturn(Optional.of(refreshToken));
            when(passwordEncoder.matches(rawToken, refreshToken.getTokenHash())).thenReturn(false);

            // Then
            assertThrows(RefreshTokenDontMatchRaw.class,
                    () -> refreshTokenService.refreshAccessToken(request, response));
        }

        @Test
        void shouldThrowTokenExpired() {
            // Given
            refreshToken.setExpiresAt(new Timestamp(System.currentTimeMillis() - 100000)); // expired in the past
            String rawToken = "rawToken";
            String cookieValue = tokenId.toString() + "." + rawToken;

            when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("refreshToken", cookieValue) });
            when(repository.findById(tokenId)).thenReturn(Optional.of(refreshToken));
            when(passwordEncoder.matches(rawToken, refreshToken.getTokenHash())).thenReturn(true);

            // Then
            assertThrows(RefreshTokenExpiredException.class,
                    () -> refreshTokenService.refreshAccessToken(request, response));
        }
    }
}