package beyou.beyouapp.backend.unit.user.federation;

import beyou.beyouapp.backend.exceptions.ApiErrorResponse;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import beyou.beyouapp.backend.user.federation.FederatedIdentityService;
import beyou.beyouapp.backend.user.federation.FederatedPrincipal;
import beyou.beyouapp.backend.user.federation.FederationOutcome;
import beyou.beyouapp.backend.user.federation.OidcAuthService;
import beyou.beyouapp.backend.user.federation.OidcIdTokenVerifier;
import beyou.beyouapp.backend.user.federation.OidcProviderProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The HTTP side of federated sign-in: which outcome issues tokens, which one answers 403,
 * and what that 403 looks like on the wire.
 *
 * <p>The 403 is the case worth pinning. It is the one place this endpoint returns a body
 * the clients branch on, and it used to be a hand-built map ({@code {"error": ...}}) where
 * every other refusal in the API is an {@link ApiErrorResponse}.
 */
class OidcAuthServiceUnitTest {

    private static final String SLUG = "omelhorsite";

    private OidcIdTokenVerifier verifier;
    private FederatedIdentityService federatedIdentityService;
    private TokenService tokenService;
    private RefreshTokenService refreshTokenService;
    private UserMapper userMapper;
    private OidcAuthService service;
    private HttpServletResponse response;
    private User user;

    @BeforeEach
    void setUp() {
        OidcProviderProperties properties = new OidcProviderProperties();
        OidcProviderProperties.Provider provider = new OidcProviderProperties.Provider();
        provider.setIssuer("https://id.example.test");
        provider.setClientId("beyou-web");
        properties.getProviders().put(SLUG, provider);

        verifier = mock(OidcIdTokenVerifier.class);
        federatedIdentityService = mock(FederatedIdentityService.class);
        tokenService = mock(TokenService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        userMapper = mock(UserMapper.class);
        response = mock(HttpServletResponse.class);
        service = new OidcAuthService(properties, verifier, federatedIdentityService,
                tokenService, refreshTokenService, userMapper);

        user = new User();
        user.setId(UUID.randomUUID());
        when(verifier.verify(anyString(), any())).thenReturn(new FederatedPrincipal(
                "https://id.example.test", "subject-1", "person@example.test", true, "Person", null, null));
        when(tokenService.generateJwtToken(user)).thenReturn("jwt");
        when(refreshTokenService.createRefreshToken(user)).thenReturn("refresh");
        when(userMapper.toResponseDTO(user)).thenReturn(mock(UserResponseDTO.class));
    }

    @Test
    @DisplayName("a known identity gets a session, tokens and all")
    void loggedInIssuesTokens() {
        when(federatedIdentityService.resolve(any(), any())).thenReturn(new FederationOutcome.LoggedIn(user));

        ResponseEntity<?> result = service.login(SLUG, "id-token", "Europe/Lisbon", false, response);

        assertEquals(200, result.getStatusCode().value());
        verify(tokenService).addJwtTokenToResponse(response, "jwt", "refresh");
    }

    @Test
    @DisplayName("the mobile contract carries the refresh token in the body")
    void mobileLoginCarriesTheRefreshToken() {
        when(federatedIdentityService.resolve(any(), any())).thenReturn(new FederationOutcome.LoggedIn(user));

        ResponseEntity<?> result = service.login(SLUG, "id-token", null, true, response);

        Map<?, ?> body = assertInstanceOf(Map.class, result.getBody());
        assertEquals("refresh", body.get("refreshToken"));
        verify(tokenService).addJwtTokenToResponse(response, "jwt", "refresh", true);
    }

    @Test
    @DisplayName("link-required is a 403 in the standard error envelope, and no session")
    void linkRequiredIsAStandard403() {
        when(federatedIdentityService.resolve(any(), any())).thenReturn(new FederationOutcome.LinkRequired(
                FederationOutcome.LinkRequired.Reason.ACCOUNT_EXISTS, "person@example.test"));

        ResponseEntity<?> result = service.login(SLUG, "id-token", null, false, response);

        assertEquals(403, result.getStatusCode().value());
        ApiErrorResponse body = assertInstanceOf(ApiErrorResponse.class, result.getBody());
        assertEquals(ErrorKey.FEDERATED_LINK_REQUIRED.name(), body.errorKey());
        assertEquals("ACCOUNT_EXISTS", body.details().get("reason"));
        assertEquals(SLUG, body.details().get("provider"));
        verify(tokenService, never()).generateJwtToken(any());
        verify(tokenService, never()).addJwtTokenToResponse(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("an unconfigured provider is refused before the token is even read")
    void unknownProviderIsRefused() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.login("nobody", "id-token", null, false, response));

        assertEquals(ErrorKey.OIDC_PROVIDER_UNKNOWN, e.getErrorKey());
        verifyNoInteractions(verifier, federatedIdentityService);
    }
}
