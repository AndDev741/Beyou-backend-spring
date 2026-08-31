package beyou.beyouapp.backend.unit.user;

import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.GoogleIdTokenVerifierService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserServiceGoogleOAuth;
import beyou.beyouapp.backend.user.dto.GoogleUserDTO;
import beyou.beyouapp.backend.user.federation.FederatedIdentityService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Google sign-in must obey the same verification gate the password door does.
 *
 * <p>Before this, {@code UserService.doLogin} was the ONLY reader of {@code emailVerified}
 * in the backend, so an account the password door refused walked in through this one. The
 * damage was not just an accidental workaround for a lost verification mail: someone can
 * register another person's address with a password of their choosing, and that unverified
 * row would then swallow the real owner's Google sign-in. The owner fills it with their
 * data, and the day anyone follows the verification link that arrived at registration, the
 * squatter's password opens the account.
 *
 * <p>Exercised through {@code googleMobileAuth}, which is the path whose identity source
 * can be mocked. The web {@code googleAuth} calls out to Google over HTTP before it reaches
 * the same guard; both call {@code isUnverifiedLocalAccount}.
 */
class GoogleAuthUnverifiedAccountUnitTest {

    private UserRepository userRepository;
    private TokenService tokenService;
    private RefreshTokenService refreshTokenService;
    private GoogleIdTokenVerifierService verifier;
    private UserServiceGoogleOAuth service;
    private HttpServletResponse response;

    private static final String EMAIL = "victim@example.com";
    private static final String ID_TOKEN = "a-verified-google-id-token";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenService = mock(TokenService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        verifier = mock(GoogleIdTokenVerifierService.class);
        UserMapper userMapper = mock(UserMapper.class);
        // A real record, not a mock: UserResponseDTO is final, and the success body is a
        // Map.of, which rejects null values. Only its non-nullness matters here.
        when(userMapper.toResponseDTO(any(User.class))).thenReturn(
                new UserResponseDTO(UUID.randomUUID(), "Victim", EMAIL, null, null, 0, false, null,
                        false, List.of(), null, 0, 0, 0, 0, null, false, 0, false, null, "UTC", null, null, null));
        response = new MockHttpServletResponse();

        // Bookkeeping only: the federated row is written after the decision this test is
        // about, and recordGoogleIdentity swallows its own failures on purpose. A mock
        // that does nothing is the honest stand-in.
        service = new UserServiceGoogleOAuth(tokenService, refreshTokenService, userRepository,
                userMapper, verifier, mock(FederatedIdentityService.class));

        when(verifier.verify(ID_TOKEN))
                .thenReturn(new GoogleUserDTO(EMAIL, "Victim", null));
        when(tokenService.generateJwtToken(any())).thenReturn("jwt");
        when(refreshTokenService.createRefreshToken(any())).thenReturn("refresh");
    }

    /** A password account, exactly as registration leaves it before anyone clicks the link. */
    private User unverifiedLocalAccount() {
        User user = new User(new UserRegisterDTO("Squatter", EMAIL, "AttackerPassword1!", null));
        user.setEmailVerified(false);
        return user;
    }

    @Test
    @DisplayName("an unverified password account cannot be entered through Google")
    void googleRefusesUnverifiedLocalAccount() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedLocalAccount()));

        ResponseEntity<Map<String, Object>> result = service.googleMobileAuth(ID_TOKEN, null, response);

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
        assertEquals("EMAIL_NOT_VERIFIED", result.getBody().get("error"),
                "the refusal has to match doLogin's, or the clients need a second branch for it");
        verify(tokenService, never()).generateJwtToken(any());
        verify(refreshTokenService, never()).createRefreshToken(any());
    }

    @Test
    @DisplayName("a verified password account may still link Google")
    void googleAllowsVerifiedLocalAccount() {
        User user = unverifiedLocalAccount();
        user.setEmailVerified(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        ResponseEntity<Map<String, Object>> result = service.googleMobileAuth(ID_TOKEN, null, response);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(tokenService).generateJwtToken(user);
    }

    @Test
    @DisplayName("a returning Google account is untouched by the gate")
    void googleAllowsExistingGoogleAccount() {
        User googleUser = new User(new GoogleUserDTO(EMAIL, "Victim", null));
        assertTrue(googleUser.isEmailVerified(), "the Google constructor is what makes this row verified");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(googleUser));

        ResponseEntity<Map<String, Object>> result = service.googleMobileAuth(ID_TOKEN, null, response);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    @DisplayName("a brand-new Google sign-up is created and let in")
    void googleCreatesUnknownAccount() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<Map<String, Object>> result = service.googleMobileAuth(ID_TOKEN, null, response);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(userRepository).save(any(User.class));
    }
}
