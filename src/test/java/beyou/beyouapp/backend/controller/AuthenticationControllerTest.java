package beyou.beyouapp.backend.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.security.passwordreset.PasswordResetToken;
import beyou.beyouapp.backend.security.passwordreset.PasswordResetTokenRepository;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AuthenticationControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;

    @BeforeEach
    void setup() {
        userRepository.deleteAll(); // Clean before all tests
        UserRegisterDTO register = new UserRegisterDTO("test", "testebeyou@gmail.com", "123456", false);
        userService.registerUser(register);
    }

    @Test
    public void shouldPassIfUserIsAuthenticated() throws Exception {
        mockMvc.perform(get("/auth/verify")
                .header("authorization", "Bearer " + simulateLogin().getResponse().getHeader("accessToken")))
                .andExpect(status().isOk());
    }

    @Test
    public void shouldMakeLoginSuccessfully() throws Exception {
        mockMvc.perform(post("/auth/login")
                .content("{\"email\": \"testebeyou@gmail.com\", \"password\": \"123456\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.name").exists())
                .andExpect(jsonPath("$.success.email").value("testebeyou@gmail.com"))
                .andExpect(cookie().exists("refreshToken"));
    }

    @Test
    public void shouldRegisterAUserSuccessfully() throws Exception {
        mockMvc.perform(post("/auth/register")
                .content("{\"name\": \"test\", \"email\": \"newtestbeyou5@gmail.com\", \"password\": \"123456\", " +
                        "\"isGoogleAccount\": false}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("User registered successfully"));
    }

    @Test
    public void shouldRefreshToken() throws Exception {
        MvcResult loginResult = simulateLogin();
        Cookie refreshTokenCookie = loginResult.getResponse().getCookie("refreshToken");

        mockMvc.perform(post("/auth/refresh")
                .cookie(refreshTokenCookie))
                .andExpect(status().isOk())
                .andExpect(header().exists("accessToken"));
    }

    @Test
    public void shouldLogoutUserSuccessfullyAndNotAcceptOtherConnectionWithSameRefreshToken() throws Exception {
        MvcResult loginResult = simulateLogin();
        Cookie refreshTokenCookie = loginResult.getResponse().getCookie("refreshToken");

        mockMvc.perform(post("/auth/logout")
                .cookie(refreshTokenCookie))
                .andExpect(status().isOk())
                .andExpect(content().string("Logged out successfully"))
                .andExpect(cookie().value("refreshToken", ""))
                .andExpect(cookie().maxAge("refreshToken", 0));

        // Try to refresh token with the same refresh token after logout
        mockMvc.perform(post("/auth/refresh")
                .cookie(refreshTokenCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Refresh token expired or already revoked"));
    }

    @Test
    public void shouldLogoutEvenWithoutARefreshToken() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(content().string("Logged out successfully"));
    }

    //Error Messages

    @Test
    public void shouldNotPassIfUserAreNotAuthenticated() throws Exception {
        mockMvc.perform(get("/auth/verify")
                        .cookie(new Cookie("jwt", "invalid")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void shouldReturnIncorrectEmailOrPasswordWhenPassedAIncorrectEmail() throws Exception {
        mockMvc.perform(post("/auth/login")
                .content("{\"email\": \"Incorrect@gmail.com\", \"password\": \"123456\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Email or password incorrect"));
    }

    @Test
    public void shouldReturnIncorrectEmailOrPasswordWhenPassedAIncorrectPassword() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"testebeyou@gmail.com\", \"password\": \"incorrect\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Email or password incorrect"));
    }

    @Test
    public void shouldReturnAErrorMessageOfEmailAlreadyInUseIfTryingToRegisterAEmailAlreadyRegistered() throws Exception {
        String jsonRequest = "{\"name\": \"required\", \"email\": \"testebeyou@gmail.com\", \"password\": \"123456\", " +
                        "\"isGoogleAccount\": false}";
        System.out.println("[LOG] JsonRequest => " + jsonRequest);
        mockMvc.perform(post("/auth/register")
                .content(jsonRequest)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(jsonPath("$.error").value("Email already in use"));
    }

    @Test
    public void shouldReturnAErrorMessageOfMissingNameIfTryingToRegisterWithoutAName() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"    \", \"email\": \"newtestbeyou4@gmail.com\", \"password\": \"123456\", " +
                        "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.name").value("Name is Required"));
    }

    @Test
    public void shouldReturnAErrorMessageOfMinimumCharacterForNameIfTryingToRegisterANameSmallerThan2Characters() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"a\", \"email\": \"newtestbeyou4@gmail.com\", \"password\": \"123456\", " +
                                "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.name").value("Name require a minimum of 2 characters"));
    }

    @Test
    public void shouldReturnErrorWhenEmailIsMissingIfTryingToRegisterWithoutAEmail() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"namename\", \"email\": \"\", \"password\": \"123456\", " +
                                "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.email").value("Email is Required"));
    }

    @Test
    public void shouldReturnAErrorMessageOfIncorrectEmailIfTryingToRegisterWithAInvalidEmail() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"namename\", \"email\": \"email.com\", \"password\": \"123456\", " +
                        "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.email").value("Email is invalid"));
    }

    @Test
    public void shouldReturnAErrorMessageOfMinimumCharactersIfTryingToRegisterAPasswordWithLessThan6Characters() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"namename\", \"email\": \"newtestbeyou4@gmail.com\", \"password\": \"1234\", " +
                                "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.password").value("Password require a minimum of 6 characters"));
    }

    @Test
    public void shouldRequestPasswordResetSuccessfully() throws Exception {
        mockMvc.perform(post("/auth/forgot-password")
                .content("{\"email\": \"testebeyou@gmail.com\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, passwordResetTokenRepository.count());
    }

    @Test
    public void shouldValidateResetTokenSuccessfully() throws Exception {
        String token = createResetTokenForUser("testebeyou@gmail.com");

        mockMvc.perform(get("/auth/reset-password/validate")
                .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    public void shouldResetPasswordSuccessfully() throws Exception {
        String token = createResetTokenForUser("testebeyou@gmail.com");

        mockMvc.perform(post("/auth/reset-password")
                .content("{\"token\": \"" + token + "\", \"password\": \"654321\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/auth/login")
                .content("{\"email\": \"testebeyou@gmail.com\", \"password\": \"654321\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    public void shouldRejectUsedResetToken() throws Exception {
        String token = createResetTokenForUser("testebeyou@gmail.com");

        mockMvc.perform(post("/auth/reset-password")
                .content("{\"token\": \"" + token + "\", \"password\": \"654321\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get("/auth/reset-password/validate")
                .param("token", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("PASSWORD_RESET_TOKEN_USED"));
    }

    private MvcResult simulateLogin() throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"testebeyou@gmail.com\", \"password\": \"123456\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("accessToken"))
                .andReturn();
    }

    private String createResetTokenForUser(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        String rawToken = "raw-reset-token";
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setCreatedAt(Timestamp.from(Instant.now()));
        token.setExpiresAt(Timestamp.from(Instant.now().plus(Duration.ofMinutes(30))));
        token.setTokenHash(passwordEncoder.encode(rawToken));
        passwordResetTokenRepository.save(token);
        return token.getId() + "." + rawToken;
    }
}
