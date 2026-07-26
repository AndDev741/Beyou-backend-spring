package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.enums.UserRole;

import com.jayway.jsonpath.JsonPath;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U5 — what the admin's two write actions do to the user's inbox.
 *
 * R14: writing a reply through the console reaches the submitter, exactly once.
 * R15/KD4: moving a submission between triage states reaches nobody — status is
 * an internal tool, and a bare "closed" arriving with no message reads worse
 * than silence.
 *
 * Registration also sends mail on this context, so assertions filter captured
 * messages by subject rather than counting sends.
 */
@AutoConfigureMockMvc
// The mocked JavaMailSender is not a JavaMailSenderImpl, which the mail health
// contributor requires — it would fail context startup on an empty bean map.
@TestPropertySource(properties = "management.health.mail.enabled=false")
class FeedbackAdminNotificationIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin-notify-admin@beyou.test";
    private static final String AUTHOR_EMAIL = "admin-notify-author@beyou.test";
    private static final String PASSWORD = "TestPassword1!";

    /** Subject fragments that identify each feedback mail, in either language. */
    private static final List<String> ACK_SUBJECTS = List.of("we got your feedback", "recebemos seu feedback");
    private static final List<String> REPLY_SUBJECTS = List.of("we replied", "respondemos seu feedback");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserService userService;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    FeedbackRepository feedbackRepository;

    @Autowired
    FeedbackReplyRepository feedbackReplyRepository;

    @MockitoBean
    JavaMailSender mailSender;

    private String adminToken;
    private String authorToken;

    @BeforeEach
    void setUp() throws Exception {
        // A mocked sender returns null from createMimeMessage() by default,
        // which would fail before a subject is ever set.
        doAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null))
                .when(mailSender).createMimeMessage();

        recreateUser(ADMIN_EMAIL, "notify admin", UserRole.ADMIN);
        recreateUser(AUTHOR_EMAIL, "notify author", UserRole.USER);

        adminToken = login(ADMIN_EMAIL);
        authorToken = login(AUTHOR_EMAIL);
    }

    @AfterEach
    void tearDown() {
        deleteUser(ADMIN_EMAIL);
        deleteUser(AUTHOR_EMAIL);
    }

    @Test
    @DisplayName("a status transition through the console notifies nobody")
    void statusTransitionNotifiesNobody() throws Exception {
        UUID id = submit("OTHER", "Just saying hello, no answer needed.");
        awaitMessage(ACK_SUBJECTS);

        mockMvc.perform(put("/feedback/admin/items/" + id + "/status")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\"}"))
                .andExpect(status().isOk());

        // The acknowledgement above already proves mail works on this context,
        // so silence here is a real result and not broken wiring.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(messagesMatching(REPLY_SUBJECTS))
                        .as("a status change must emit no mail of its own")
                        .isEmpty());

        assertThat(messagesMatching(ACK_SUBJECTS))
                .as("the acknowledgement must not be re-sent by a status change")
                .hasSize(1);
        assertThat(feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id)).isEmpty();
        assertThat(feedbackRepository.findById(id).orElseThrow().getStatus()).isEqualTo(FeedbackStatus.CLOSED);
    }

    @Test
    @DisplayName("a reply written through the console is stored and delivered exactly once")
    void replyThroughTheConsoleIsStoredAndDeliveredExactlyOnce() throws Exception {
        UUID id = submit("FEATURE_REQUEST", "Let me drag routine sections around.");
        awaitMessage(ACK_SUBJECTS);

        mockMvc.perform(post("/feedback/admin/items/" + id + "/replies")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Drag-and-drop ships next release. Thanks for the nudge!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Drag-and-drop ships next release. Thanks for the nudge!"));

        awaitMessage(REPLY_SUBJECTS);

        // Settle, then assert the count — one reply must not fan out into two mails.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(messagesMatching(REPLY_SUBJECTS))
                        .as("one written reply must produce exactly one notification")
                        .hasSize(1));

        assertThat(feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id)).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private UUID submit(String category, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"" + category + "\", \"body\": \"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private void awaitMessage(List<String> subjectFragments) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(messagesMatching(subjectFragments)).isNotEmpty());
    }

    private List<MimeMessage> messagesMatching(List<String> subjectFragments) {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream()
                .filter(message -> {
                    String subject = subjectOf(message).toLowerCase();
                    return subjectFragments.stream().anyMatch(subject::contains);
                })
                .toList();
    }

    private static String subjectOf(MimeMessage message) {
        try {
            String subject = message.getSubject();
            return subject == null ? "" : subject;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the mail subject", e);
        }
    }

    private User recreateUser(String email, String name, UserRole role) {
        deleteUser(email);
        userService.registerUser(new UserRegisterDTO(name, email, PASSWORD));

        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user.setUserRole(role);
        user.setLanguageInUse("en");
        return userRepository.saveAndFlush(user);
    }

    private void deleteUser(String email) {
        userRepository.findByEmail(email).ifPresent(existing -> {
            feedbackReplyRepository.deleteAll(feedbackReplyRepository.findAll().stream()
                    .filter(reply -> reply.getAuthor() != null
                            && reply.getAuthor().getId().equals(existing.getId()))
                    .toList());

            feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId())
                    .forEach(feedback -> feedbackReplyRepository.deleteAll(
                            feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedback.getId())));
            feedbackRepository.deleteAll(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId()));

            refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(existing.getId()));
            userRepository.delete(existing);
        });
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Access-Token"))
                .andReturn();

        return result.getResponse().getHeader("X-Access-Token");
    }
}
