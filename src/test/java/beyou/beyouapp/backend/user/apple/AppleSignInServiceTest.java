package beyou.beyouapp.backend.user.apple;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.apple.dto.AppleMobileLoginDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;

@DisplayName("AppleSignInService")
class AppleSignInServiceTest {

    private AppleIdTokenVerifier verifier;
    private AppleAccountResolver resolver;
    private AppleTokenClient tokenClient;
    private TokenService tokenService;
    private RefreshTokenService refreshTokenService;
    private UserMapper userMapper;
    private AppleSignInService service;
    private MockHttpServletResponse response;

    private final User user = new User();
    private final UUID linkId = UUID.randomUUID();
    private static final AppleIdentity IDENTITY =
            new AppleIdentity("sub-1", "ana@example.test", true, false, "com.beyou.mobile");

    private static UserResponseDTO dummyDto() {
        return new UserResponseDTO(UUID.randomUUID(), "Ana", "ana@example.test", null, null, 0, false, null, true,
                List.of(), null, 0d, 0d, 0d, 0, null, false, 0, false, null, null, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        verifier = mock(AppleIdTokenVerifier.class);
        resolver = mock(AppleAccountResolver.class);
        tokenClient = mock(AppleTokenClient.class);
        tokenService = mock(TokenService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        userMapper = mock(UserMapper.class);
        service = new AppleSignInService(verifier, resolver, tokenClient, tokenService, refreshTokenService, userMapper);
        response = new MockHttpServletResponse();
        user.setId(UUID.randomUUID());

        when(verifier.verify("id-token", "nonce")).thenReturn(IDENTITY);
        when(tokenService.generateJwtToken(user)).thenReturn("jwt");
        when(refreshTokenService.createRefreshToken(user)).thenReturn("refresh");
        when(userMapper.toResponseDTO(user)).thenReturn(dummyDto());
    }

    private static AppleMobileLoginDTO request(String code, String given, String family) {
        return new AppleMobileLoginDTO("id-token", "nonce", code, given, family, "Europe/Lisbon", "pt");
    }

    @Test
    @DisplayName("issues the session on the mobile contract, same as Google's mobile door")
    void issuesMobileSession() {
        when(resolver.resolve(IDENTITY, "Ana Silva", "Europe/Lisbon", "pt"))
                .thenReturn(new AppleAccountResolver.Outcome.SignedIn(user, linkId, false));

        ResponseEntity<Map<String, Object>> result = service.mobileSignIn(request(null, "Ana", "Silva"), response);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).containsEntry("refreshToken", "refresh").containsKey("success");
        verify(tokenService).addJwtTokenToResponse(response, "jwt", "refresh", true);
    }

    @Test
    @DisplayName("a squatted, unverified password account gets the EMAIL_NOT_VERIFIED body /auth/login uses")
    void unverifiedAccountIsRefused() {
        when(resolver.resolve(any(), anyString(), any(), any()))
                .thenReturn(new AppleAccountResolver.Outcome.EmailNotVerified());

        ResponseEntity<Map<String, Object>> result = service.mobileSignIn(request(null, null, null), response);

        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(result.getBody()).containsEntry("error", "EMAIL_NOT_VERIFIED");
        verify(tokenService, never()).generateJwtToken(any());
    }

    @Test
    @DisplayName("with the key configured, the code is exchanged once and the refresh token kept")
    void keepsTheRefreshToken() {
        when(resolver.resolve(any(), anyString(), any(), any()))
                .thenReturn(new AppleAccountResolver.Outcome.SignedIn(user, linkId, false));
        when(tokenClient.isConfigured()).thenReturn(true);
        when(tokenClient.exchangeAuthorizationCode("code-1", "com.beyou.mobile"))
                .thenReturn(Optional.of("apple-refresh"));

        service.mobileSignIn(request("code-1", null, null), response);

        verify(resolver).storeRefreshToken(linkId, "apple-refresh", "com.beyou.mobile");
    }

    @Test
    @DisplayName("a link that already holds a refresh token is not exchanged again")
    void doesNotExchangeTwice() {
        when(resolver.resolve(any(), anyString(), any(), any()))
                .thenReturn(new AppleAccountResolver.Outcome.SignedIn(user, linkId, true));
        when(tokenClient.isConfigured()).thenReturn(true);

        service.mobileSignIn(request("code-1", null, null), response);

        verify(tokenClient, never()).exchangeAuthorizationCode(anyString(), anyString());
    }

    @Test
    @DisplayName("without the key the sign-in still succeeds and nothing is exchanged")
    void unconfiguredKeyStillSignsIn() {
        when(resolver.resolve(any(), anyString(), any(), any()))
                .thenReturn(new AppleAccountResolver.Outcome.SignedIn(user, linkId, false));
        when(tokenClient.isConfigured()).thenReturn(false);

        ResponseEntity<Map<String, Object>> result = service.mobileSignIn(request("code-1", null, null), response);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(tokenClient, never()).exchangeAuthorizationCode(anyString(), anyString());
        verify(resolver, never()).storeRefreshToken(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("an exchange that blows up never costs the sign-in")
    void exchangeFailureIsNotFatal() {
        when(resolver.resolve(any(), anyString(), any(), any()))
                .thenReturn(new AppleAccountResolver.Outcome.SignedIn(user, linkId, false));
        when(tokenClient.isConfigured()).thenReturn(true);
        when(tokenClient.exchangeAuthorizationCode(anyString(), anyString()))
                .thenThrow(new IllegalStateException("APPLE_PRIVATE_KEY is not a valid .p8 (PKCS#8 EC) key"));

        ResponseEntity<Map<String, Object>> result = service.mobileSignIn(request("code-1", null, null), response);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("display name: Apple's name, else the address before the @, never a relay's random letters")
    void displayName() {
        assertThat(AppleSignInService.displayName(" Ana ", " Silva ", IDENTITY)).isEqualTo("Ana Silva");
        assertThat(AppleSignInService.displayName("Ana", null, IDENTITY)).isEqualTo("Ana");
        assertThat(AppleSignInService.displayName(null, null, IDENTITY)).isEqualTo("ana");
        AppleIdentity relay = new AppleIdentity("s", "x7k2abcd9q@privaterelay.appleid.com", true, true, "a");
        assertThat(AppleSignInService.displayName(null, "", relay)).isEqualTo("Beyou");
        AppleIdentity noEmail = new AppleIdentity("s", null, false, false, "a");
        assertThat(AppleSignInService.displayName(null, null, noEmail)).isEqualTo("Beyou");
    }
}
