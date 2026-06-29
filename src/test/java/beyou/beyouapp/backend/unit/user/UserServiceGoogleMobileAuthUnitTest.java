package beyou.beyouapp.backend.unit.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.GoogleIdTokenVerifierService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserServiceGoogleOAuth;
import beyou.beyouapp.backend.user.dto.GoogleUserDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceGoogleOAuth.googleMobileAuth")
class UserServiceGoogleMobileAuthUnitTest {

    @Mock private TokenService tokenService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private UserRepository userRepository;
    @Mock private UserMapper userMapper;
    @Mock private GoogleIdTokenVerifierService googleIdTokenVerifierService;

    private UserServiceGoogleOAuth service;
    private MockHttpServletResponse response;

    private static final String ID_TOKEN = "google-id-token";
    private static final GoogleUserDTO GOOGLE_USER =
            new GoogleUserDTO("alice@example.com", "Alice", "http://pic");

    // UserResponseDTO is a final record (can't be mocked with the subclass mock-maker),
    // so build a throwaway instance to stand in for the mapper output.
    private static UserResponseDTO dummyDto() {
        return new UserResponseDTO("Alice", "alice@example.com", null, null, 0, null, true,
                List.of(), null, 0d, 0d, 0d, 0, null, false, 0, false, null, null, null);
    }

    @BeforeEach
    void setUp() {
        service = new UserServiceGoogleOAuth(tokenService, refreshTokenService, userRepository, userMapper,
                googleIdTokenVerifierService);
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("existing user: issues tokens with the mobile contract and returns the profile + refresh token")
    void existingUser_returnsProfileAndRefreshTokenInBody() {
        User user = new User(GOOGLE_USER);
        UserResponseDTO dto = dummyDto();
        when(googleIdTokenVerifierService.verify(ID_TOKEN)).thenReturn(GOOGLE_USER);
        when(userRepository.findByEmail(GOOGLE_USER.email())).thenReturn(Optional.of(user));
        when(tokenService.generateJwtToken(user)).thenReturn("jwt");
        when(refreshTokenService.createRefreshToken(user)).thenReturn("refresh");
        when(userMapper.toResponseDTO(user)).thenReturn(dto);

        ResponseEntity<Map<String, Object>> result = service.googleMobileAuth(ID_TOKEN, response);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).containsEntry("success", dto).containsEntry("refreshToken", "refresh");
        // mobile=true → refresh token rides in the body, not a cookie
        verify(tokenService).addJwtTokenToResponse(response, "jwt", "refresh", true);
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("new user: creates the account from the verified Google profile")
    void newUser_createsAccount() {
        when(googleIdTokenVerifierService.verify(ID_TOKEN)).thenReturn(GOOGLE_USER);
        when(userRepository.findByEmail(GOOGLE_USER.email())).thenReturn(Optional.empty());
        when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(tokenService.generateJwtToken(org.mockito.ArgumentMatchers.any(User.class))).thenReturn("jwt");
        when(refreshTokenService.createRefreshToken(org.mockito.ArgumentMatchers.any(User.class))).thenReturn("refresh");
        when(userMapper.toResponseDTO(org.mockito.ArgumentMatchers.any(User.class)))
                .thenReturn(dummyDto());

        ResponseEntity<Map<String, Object>> result = service.googleMobileAuth(ID_TOKEN, response);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(GOOGLE_USER.email());
        assertThat(saved.getValue().isGoogleAccount()).isTrue();
        assertThat(result.getBody()).containsEntry("refreshToken", "refresh");
    }

    @Test
    @DisplayName("invalid token: propagates the verifier error and issues no tokens")
    void invalidToken_propagatesAndIssuesNothing() {
        when(googleIdTokenVerifierService.verify(ID_TOKEN))
                .thenThrow(new BusinessException(ErrorKey.INVALID_REQUEST, "Invalid Google ID token"));

        assertThatThrownBy(() -> service.googleMobileAuth(ID_TOKEN, response))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid Google ID token");

        verify(userRepository, never()).findByEmail(org.mockito.ArgumentMatchers.anyString());
        verify(tokenService, never()).addJwtTokenToResponse(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }
}
