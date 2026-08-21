package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.enums.UserRole;

import com.jayway.jsonpath.JsonPath;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mail that goes TO the console when a submission lands.
 *
 * Not to be confused with {@code FeedbackAdminNotificationIntegrationTest},
 * which covers the opposite direction: mail the admin's own actions send to the
 * submitting user. This one is about the owner finding out that somebody wrote
 * in, without having to open the app to check.
 *
 * Everything here asserts per recipient rather than by counting sends. Other
 * tests on this shared context create admin accounts of their own, and
 * registration mails on every setUp, so a global tally would be measuring the
 * suite instead of the feature.
 */
@AutoConfigureMockMvc
// The mocked JavaMailSender is not a JavaMailSenderImpl, which the mail health
// contributor requires — it would fail context startup on an empty bean map.
//
// frontend.url carries a trailing slash on purpose. The test profile's value is
// the bare word "test", which would let a link built by naive concatenation
// pass; a real base URL with the slash that FRONTEND_URL actually ships with
// locally is the fixture that catches "https://host//admin/feedback".
@TestPropertySource(properties = {
        "management.health.mail.enabled=false",
        "frontend.url=https://app.beyouweb.com/"
})
class FeedbackAdminAlertIntegrationTest extends AbstractIntegrationTest {

    private static final String FIRST_ADMIN = "inbox-alert-admin-one@beyou.test";
    private static final String SECOND_ADMIN = "inbox-alert-admin-two@beyou.test";
    private static final String AUTHOR = "inbox-alert-author@beyou.test";
    private static final String PASSWORD = "TestPassword1!";

    private static final String ALERT_SUBJECT = "new feedback in the beyou inbox";
    private static final List<String> ACK_SUBJECTS = List.of("we got your feedback", "recebemos seu feedback");
    private static final String CONSOLE_LINK = "https://app.beyouweb.com/admin/feedback";

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

    private String authorToken;
    private String firstAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        // A mocked sender returns null from createMimeMessage() by default,
        // which would fail before a subject is ever set.
        doAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null))
                .when(mailSender).createMimeMessage();

        recreateUser(FIRST_ADMIN, "inbox alert admin one", UserRole.ADMIN);
        recreateUser(SECOND_ADMIN, "inbox alert admin two", UserRole.ADMIN);
        recreateUser(AUTHOR, "inbox alert author", UserRole.USER);

        authorToken = login(AUTHOR);
        firstAdminToken = login(FIRST_ADMIN);
    }

    @AfterEach
    void tearDown() {
        deleteUser(FIRST_ADMIN);
        deleteUser(SECOND_ADMIN);
        deleteUser(AUTHOR);
    }

    @Test
    @DisplayName("a submission alerts every admin account, once each, with a link to the console")
    void aSubmissionAlertsEveryAdminOnce() throws Exception {
        submit(authorToken, "BUG", "The routine list scrolls back to the top after a check.");

        awaitAlert(FIRST_ADMIN);
        awaitAlert(SECOND_ADMIN);

        // Settle before counting: one submission fanning out into two mails per
        // admin is exactly the kind of thing a passing "at least one" hides.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> {
                    assertThat(alertsTo(FIRST_ADMIN))
                            .as("one submission must alert each admin exactly once")
                            .hasSize(1);
                    assertThat(alertsTo(SECOND_ADMIN)).hasSize(1);
                });

        assertThat(alertsTo(AUTHOR))
                .as("the alert is for the console, not for the person who wrote in")
                .isEmpty();

        String body = bodyOf(alertsTo(FIRST_ADMIN).getFirst());
        assertThat(body)
                .as("the alert must link to the console, with no doubled slash from the base URL")
                .contains(CONSOLE_LINK)
                .doesNotContain("app.beyouweb.com//admin");
    }

    @Test
    @DisplayName("the alert carries the link only, never what the user wrote")
    void theAlertCarriesTheLinkOnlyNeverTheFeedbackBody() throws Exception {
        String secret = "My therapist said I should track this and I would rather nobody read it.";
        submit(authorToken, "OTHER", secret);

        awaitAlert(FIRST_ADMIN);

        String body = bodyOf(alertsTo(FIRST_ADMIN).getFirst());
        assertThat(body)
                .as("feedback text must not leave the database for a mail provider")
                .doesNotContain("therapist")
                .doesNotContain(AUTHOR);
    }

    @Test
    @DisplayName("an admin who writes feedback is not alerted about their own message")
    void anAdminWritingFeedbackIsNotAlertedAboutTheirOwnMessage() throws Exception {
        submit(firstAdminToken, "FEATURE_REQUEST", "Let me pin a routine section to the top.");

        // The other admin's alert is the proof that the wiring ran at all, so
        // the silence below is a result rather than a mail that never fired.
        awaitAlert(SECOND_ADMIN);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(alertsTo(FIRST_ADMIN))
                        .as("the author already has the acknowledgement; a second mail is noise")
                        .isEmpty());

        assertThat(messagesTo(FIRST_ADMIN, ACK_SUBJECTS))
                .as("the admin still gets the ordinary acknowledgement for their own submission")
                .hasSize(1);
    }

    @Test
    @DisplayName("a failing acknowledgement still leaves the console alerted")
    void aFailingAcknowledgementStillLeavesTheConsoleAlerted() throws Exception {
        // The acknowledgement is sent first. Sharing one try/catch with the
        // alert would let the author's dead mailbox hide every submission from
        // the console — the failure this test exists to prevent.
        doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            if (isTo(message, AUTHOR)) {
                throw new IllegalStateException("SMTP said no");
            }
            return null;
        }).when(mailSender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));

        UUID id = submit(authorToken, "BUG", "Check-in XP shows up twice on a slow connection.");

        awaitAlert(FIRST_ADMIN);
        awaitAlert(SECOND_ADMIN);

        assertThat(feedbackRepository.findById(id))
                .as("a mail failure of any kind must not cost the submission")
                .isPresent();
    }

    // ---------------------------------------------------------------- helpers

    private UUID submit(String token, String category, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"" + category + "\", \"body\": \"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private void awaitAlert(String recipient) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(alertsTo(recipient))
                        .as("no inbox alert reached %s", recipient)
                        .isNotEmpty());
    }

    private List<MimeMessage> alertsTo(String recipient) {
        return messagesTo(recipient, List.of(ALERT_SUBJECT));
    }

    private List<MimeMessage> messagesTo(String recipient, List<String> subjectFragments) {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream()
                .filter(message -> isTo(message, recipient))
                .filter(message -> {
                    String subject = subjectOf(message).toLowerCase();
                    return subjectFragments.stream().anyMatch(subject::contains);
                })
                .toList();
    }

    private static boolean isTo(MimeMessage message, String recipient) {
        try {
            Address[] addresses = message.getRecipients(Message.RecipientType.TO);
            return addresses != null && Arrays.stream(addresses)
                    .anyMatch(address -> address.toString().equalsIgnoreCase(recipient));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the mail recipients", e);
        }
    }

    private static String subjectOf(MimeMessage message) {
        try {
            String subject = message.getSubject();
            return subject == null ? "" : subject;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the mail subject", e);
        }
    }

    /**
     * MimeMessageHelper is built with multipart=true, so the message content is
     * a MimeMultipart and not the HTML. Flattening it is the whole point:
     * asserting on the container's toString() would let every doesNotContain
     * in this file pass without reading a single byte of the mail.
     */
    private static String bodyOf(MimeMessage message) {
        try {
            StringBuilder text = new StringBuilder();
            collectText(message.getContent(), text);
            return text.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the mail body", e);
        }
    }

    private static void collectText(Object content, StringBuilder text) throws Exception {
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collectText(multipart.getBodyPart(i).getContent(), text);
            }
        } else if (content != null) {
            text.append(content);
        }
    }

    private User recreateUser(String email, String name, UserRole role) {
        deleteUser(email);
        userService.registerUser(new UserRegisterDTO(name, email, PASSWORD, null));

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
