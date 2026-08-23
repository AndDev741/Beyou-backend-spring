package beyou.beyouapp.backend.controller;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentRepository;
import beyou.beyouapp.backend.domain.feedback.FeedbackReplyRepository;
import beyou.beyouapp.backend.domain.feedback.FeedbackRepository;
import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code POST /auth/resend-verification} — the way out of a lost registration mail.
 *
 * <p><b>Deliberately not {@code @Transactional}</b>, unlike its sibling
 * {@link AuthVerificationControllerTest}. The service mails from an {@code afterCommit}
 * hook, and a test transaction that rolls back never commits, so an assertion that a mail
 * went out would pass against a service that sends nothing. Cleanup is by hand in
 * {@link #setup()} instead.
 *
 * <p><b>Assertions read the stored row, not the mock, wherever a mail must NOT have been
 * sent.</b> Registration mails through an {@code @Async} listener whose arrival at the
 * mock is not something a test can time, so counting invocations is a coin toss:
 * {@code verifyNoInteractions} can catch a straggler from the previous line, and waiting
 * for one can time out. What the token and the cooldown stamp say afterwards is decided
 * synchronously and cannot race. The one mail assertion that remains is pinned to the
 * exact token resend minted, which no earlier mail can match.
 */
@AutoConfigureMockMvc
public class EmailVerificationResendTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    private FeedbackReplyRepository feedbackReplyRepository;
    @Autowired
    private FeedbackAttachmentRepository feedbackAttachmentRepository;
    @Autowired
    private FeedbackRepository feedbackRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserService userService;

    @MockitoBean
    private EmailService emailService;

    @BeforeEach
    void setup() {
        refreshTokenRepository.deleteAll();
        feedbackReplyRepository.deleteAll();
        feedbackAttachmentRepository.deleteAll();
        feedbackRepository.deleteAll();
        userRepository.deleteAll();
        reset(emailService);
    }

    private User register(String email) {
        userService.registerUser(new UserRegisterDTO("test", email, "TestPassword1!", null));
        return userRepository.findByEmail(email).orElseThrow();
    }

    /** Puts the last send outside the cooldown, the way waiting a minute would. */
    private User leaveCooldown(User user) {
        user.setVerificationTokenSentAt(Instant.now().minusSeconds(3600));
        return userRepository.save(user);
    }

    private User reload(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    private void postResend(String email) throws Exception {
        mockMvc.perform(post("/auth/resend-verification")
                        .content("{\"email\": \"" + email + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists());
    }

    private void expectLoginRefused(String email) throws Exception {
        mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"" + email + "\", \"password\": \"TestPassword1!\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("the whole repair: stranded by an expired token, resend, verify, log in")
    void strandedAccountRecoversEndToEnd() throws Exception {
        User user = register("stranded@test.com");
        String deadToken = user.getVerificationToken();

        // The trap this endpoint exists for: the 24h window closed with the mail unread.
        user.setVerificationTokenExpiry(LocalDateTime.now().minusHours(1));
        leaveCooldown(user);

        mockMvc.perform(get("/auth/verify-email").param("token", deadToken))
                .andExpect(status().isBadRequest());
        expectLoginRefused("stranded@test.com");

        postResend("stranded@test.com");

        User afterResend = reload("stranded@test.com");
        String freshToken = afterResend.getVerificationToken();
        assertNotEquals(deadToken, freshToken, "resend must mint a new token, not re-send the dead one");
        assertTrue(afterResend.getVerificationTokenExpiry().isAfter(LocalDateTime.now()),
                "the new token needs a live window, or the resend fixes nothing");
        verify(emailService).sendVerificationEmail(eq("stranded@test.com"), eq(freshToken), any());

        mockMvc.perform(get("/auth/verify-email").param("token", freshToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"stranded@test.com\", \"password\": \"TestPassword1!\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.name").exists());
    }

    @Test
    @DisplayName("the replaced token stops working, so one inbox never holds two live links")
    void previousTokenIsInvalidated() throws Exception {
        User user = leaveCooldown(register("rotate@test.com"));
        String firstToken = user.getVerificationToken();

        postResend("rotate@test.com");
        assertNotEquals(firstToken, reload("rotate@test.com").getVerificationToken());

        mockMvc.perform(get("/auth/verify-email").param("token", firstToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("an unknown address gets the same 200 and no mail, so the endpoint cannot be asked who exists")
    void unknownAddressIsIndistinguishable() throws Exception {
        // Nothing is registered in this test, so no async registration mail can be in
        // flight and counting invocations is safe here.
        postResend("nobody@test.com");
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("an already-verified address gets the same 200 and nothing is re-issued")
    void verifiedAddressIsIndistinguishable() throws Exception {
        User user = leaveCooldown(register("done@test.com"));
        mockMvc.perform(get("/auth/verify-email").param("token", user.getVerificationToken()))
                .andExpect(status().isOk());
        assertNull(reload("done@test.com").getVerificationToken(), "verifying clears the token");

        postResend("done@test.com");

        // A resend that ran would have minted one. Still null means it returned early,
        // and it did so behind the same 200 postResend already asserted.
        assertNull(reload("done@test.com").getVerificationToken(),
                "a verified account must not be issued a fresh verification token");
    }

    /**
     * The one that separates this from the password-reset flow it was modelled on.
     * That one throws PASSWORD_RESET_TOO_MANY_REQUESTS, a 400 with a named key, where an
     * unknown address gets a 200 — which tells a caller the address exists. Here both are
     * a 200 with the same body, and only the untouched token says the cooldown bit.
     */
    @Test
    @DisplayName("the cooldown refuses silently: same 200, token and stamp untouched")
    void cooldownIsSilent() throws Exception {
        User user = register("fast@test.com");           // registration stamps the cooldown
        String tokenBefore = user.getVerificationToken();
        Instant stampBefore = user.getVerificationTokenSentAt();
        assertNotNull(stampBefore);

        postResend("fast@test.com");

        User after = reload("fast@test.com");
        assertEquals(tokenBefore, after.getVerificationToken(),
                "a refused resend must not burn the token in the mail already sitting in the inbox");
        assertEquals(stampBefore, after.getVerificationTokenSentAt(),
                "and it must not slide the cooldown forward, or a retrying user never gets out of it");
    }

    @Test
    @DisplayName("a send that throws gives the cooldown back, so the retry is not refused too")
    void failedSendReleasesCooldown() throws Exception {
        User user = leaveCooldown(register("bounce@test.com"));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendVerificationEmail(eq("bounce@test.com"), any(), any());

        postResend("bounce@test.com");

        User afterFailure = reload("bounce@test.com");
        assertNull(afterFailure.getVerificationTokenSentAt(),
                "the stamp must be cleared, or the user waits out a cooldown for a mail that never went");

        // And the retry gets through rather than meeting a cooldown it did not earn.
        doNothing().when(emailService).sendVerificationEmail(any(), any(), any());
        postResend("bounce@test.com");
        User afterRetry = reload("bounce@test.com");
        assertNotEquals(afterFailure.getVerificationToken(), afterRetry.getVerificationToken(),
                "the retry has to actually issue a new token");
        assertNotNull(afterRetry.getVerificationTokenSentAt());
    }

    @Test
    @DisplayName("registration still stamps the cooldown, so a double tap cannot burn the first mail")
    void registrationStartsTheCooldown() {
        assertNotNull(register("stamp@test.com").getVerificationTokenSentAt());
    }
}
