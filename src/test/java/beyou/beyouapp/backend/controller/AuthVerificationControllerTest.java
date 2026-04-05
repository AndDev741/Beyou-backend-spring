package beyou.beyouapp.backend.controller;

import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AuthVerificationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @MockitoBean
    private EmailService emailService;

    @BeforeEach
    void setup() {
        userRepository.deleteAll();
    }

    @Test
    public void shouldVerifyEmailSuccessfully() throws Exception {
        UserRegisterDTO register = new UserRegisterDTO("test", "verify@test.com", "TestPassword1!", false);
        userService.registerUser(register);

        User user = userRepository.findByEmail("verify@test.com").orElseThrow();
        assertFalse(user.isEmailVerified());
        assertNotNull(user.getVerificationToken());

        String token = user.getVerificationToken();

        mockMvc.perform(get("/auth/verify-email").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("Email verified successfully"));

        User verified = userRepository.findByEmail("verify@test.com").orElseThrow();
        assertTrue(verified.isEmailVerified());
        assertNull(verified.getVerificationToken());
        assertNull(verified.getVerificationTokenExpiry());
    }

    @Test
    public void shouldRejectInvalidToken() throws Exception {
        mockMvc.perform(get("/auth/verify-email").param("token", "invalid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }

    @Test
    public void shouldRejectExpiredToken() throws Exception {
        UserRegisterDTO register = new UserRegisterDTO("test", "expired@test.com", "TestPassword1!", false);
        userService.registerUser(register);

        User user = userRepository.findByEmail("expired@test.com").orElseThrow();
        user.setVerificationTokenExpiry(LocalDateTime.now().minusHours(1));
        userRepository.save(user);

        mockMvc.perform(get("/auth/verify-email").param("token", user.getVerificationToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }

    @Test
    public void shouldBlockLoginForUnverifiedUser() throws Exception {
        UserRegisterDTO register = new UserRegisterDTO("test", "unverified@test.com", "TestPassword1!", false);
        userService.registerUser(register);

        mockMvc.perform(post("/auth/login")
                .content("{\"email\": \"unverified@test.com\", \"password\": \"TestPassword1!\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    public void shouldAllowLoginAfterVerification() throws Exception {
        UserRegisterDTO register = new UserRegisterDTO("test", "verified@test.com", "TestPassword1!", false);
        userService.registerUser(register);

        User user = userRepository.findByEmail("verified@test.com").orElseThrow();
        String token = user.getVerificationToken();

        mockMvc.perform(get("/auth/verify-email").param("token", token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/login")
                .content("{\"email\": \"verified@test.com\", \"password\": \"TestPassword1!\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.name").exists());
    }
}
