package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackReplyRequestDTO;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;

import com.jayway.jsonpath.JsonPath;

import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;

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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U4 — acknowledgement and reply mail.
 *
 * Covers R10/KD8 (the submission is stored before any side effect, and a
 * failing send never costs it), R13 (an automatic acknowledgement once the
 * submission is stored), R14 (a written reply reaches the submitter) and
 * R15/KD4 (a status change on its own notifies nobody).
 *
 * Registration also sends mail on this context, so every assertion here
 * filters captured messages by subject rather than counting sends.
 */
@AutoConfigureMockMvc
// The mocked JavaMailSender is not a JavaMailSenderImpl, which the mail health
// contributor requires — it would fail context startup on an empty bean map.
@TestPropertySource(properties = "management.health.mail.enabled=false")
class FeedbackNotificationIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "feedback-notify@beyou.test";
    private static final String ADMIN_EMAIL = "feedback-notify-admin@beyou.test";
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

    @Autowired
    FeedbackReplyService feedbackReplyService;

    @MockitoBean
    JavaMailSender mailSender;

    private User author;
    private User admin;

    @BeforeEach
    void setUp() {
        // A mocked sender returns null from createMimeMessage() by default,
        // which would fail before a subject is ever set.
        doAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null))
                .when(mailSender).createMimeMessage();

        author = recreateUser(EMAIL, "feedback author", "en");
        admin = recreateUser(ADMIN_EMAIL, "feedback admin", "en");
    }

    @Test
    @DisplayName("a stored submission is acknowledged by email to its author")
    void storedSubmissionIsAcknowledgedByEmail() throws Exception {
        submit("BUG", "The routine check-in button does nothing on my phone.");

        MimeMessage ack = awaitMessage(ACK_SUBJECTS);

        assertThat(recipientsOf(ack)).containsExactly(EMAIL);
        assertThat(bodyOf(ack)).contains("The routine check-in button does nothing on my phone.");
    }

    @Test
    @DisplayName("a submission whose acknowledgement send throws stays stored and retrievable")
    void aFailingAcknowledgementNeverCostsTheSubmission() throws Exception {
        doThrow(new RuntimeException("SMTP is down"))
                .when(mailSender).send(any(MimeMessage.class));

        UUID id = submit("BUG", "Mail is broken but this report must survive it.");

        // The acknowledgement send was attempted…
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(messagesMatching(ACK_SUBJECTS))
                        .as("the acknowledgement must be attempted even when the transport is down")
                        .isNotEmpty());

        // …and failed, without taking the submission with it.
        Feedback stored = feedbackRepository.findById(id).orElseThrow();
        assertThat(stored.getBody()).isEqualTo("Mail is broken but this report must survive it.");
        assertThat(stored.getUser().getId()).isEqualTo(author.getId());
        assertThat(stored.getStatus()).isEqualTo(FeedbackStatus.OPEN);
    }

    @Test
    @DisplayName("writing a reply sends mail to the submission's owner")
    void writingAReplySendsMailToTheOwner() throws Exception {
        UUID id = submit("FEATURE_REQUEST", "Let me reorder routine sections by dragging them.");
        awaitMessage(ACK_SUBJECTS);

        feedbackReplyService.reply(id, admin.getId(), new CreateFeedbackReplyRequestDTO(
                "Drag-and-drop reordering ships in the next release. Thanks for the nudge!"));

        MimeMessage reply = awaitMessage(REPLY_SUBJECTS);

        assertThat(recipientsOf(reply)).containsExactly(EMAIL);
        assertThat(bodyOf(reply)).contains("Drag-and-drop reordering ships in the next release.");
        assertThat(bodyOf(reply)).contains("Let me reorder routine sections by dragging them.");

        assertThat(feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getBody())
                            .isEqualTo("Drag-and-drop reordering ships in the next release. Thanks for the nudge!");
                    assertThat(saved.getAuthor().getId()).isEqualTo(admin.getId());
                });
    }

    @Test
    @DisplayName("moving a submission to closed with no reply notifies nobody")
    void aStatusChangeAloneNotifiesNobody() throws Exception {
        UUID id = submit("OTHER", "Just saying hello, no answer needed.");
        awaitMessage(ACK_SUBJECTS);

        Feedback feedback = feedbackRepository.findById(id).orElseThrow();
        feedback.setStatus(FeedbackStatus.CLOSED);
        feedbackRepository.saveAndFlush(feedback);

        // Nothing new may be sent because of the transition. The acknowledgement
        // above already proves mail works on this context, so silence here is a
        // real result and not a broken wiring.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(capturedMessages())
                        .as("a status change must emit no mail of its own")
                        .noneSatisfy(message ->
                                assertThat(subjectOf(message)).containsAnyOf(
                                        REPLY_SUBJECTS.toArray(new String[0]))));

        assertThat(messagesMatching(ACK_SUBJECTS))
                .as("the acknowledgement must not be re-sent by a status change")
                .hasSize(1);
        assertThat(feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(id)).isEmpty();
    }

    @Test
    @DisplayName("the acknowledgement renders in the recipient's stored language")
    void acknowledgementRendersInTheStoredLanguage() throws Exception {
        author.setLanguageInUse("pt");
        userRepository.saveAndFlush(author);

        submit("BUG", "O botao de check-in nao responde.");

        MimeMessage ack = awaitMessage(ACK_SUBJECTS);

        assertThat(subjectOf(ack)).containsIgnoringCase("recebemos seu feedback");
        assertThat(bodyOf(ack)).contains("Recebemos");
        assertNoPlaceholderLeakage(bodyOf(ack));
    }

    @Test
    @DisplayName("the reply renders in the recipient's stored language")
    void replyRendersInTheStoredLanguage() throws Exception {
        author.setLanguageInUse("pt");
        userRepository.saveAndFlush(author);

        UUID id = submit("BUG", "O botao de check-in nao responde.");
        awaitMessage(ACK_SUBJECTS);

        feedbackReplyService.reply(id, admin.getId(),
                new CreateFeedbackReplyRequestDTO("Corrigido na versao 1.4.3, obrigado!"));

        MimeMessage reply = awaitMessage(REPLY_SUBJECTS);

        assertThat(subjectOf(reply)).containsIgnoringCase("respondemos seu feedback");
        assertThat(bodyOf(reply)).contains("Corrigido na versao 1.4.3, obrigado!");
        assertNoPlaceholderLeakage(bodyOf(reply));
    }

    @Test
    @DisplayName("both templates render in English with no leftover placeholders")
    void bothTemplatesRenderInEnglishWithoutPlaceholderLeakage() throws Exception {
        UUID id = submit("BUG", "English rendering check.");
        assertNoPlaceholderLeakage(bodyOf(awaitMessage(ACK_SUBJECTS)));

        feedbackReplyService.reply(id, admin.getId(),
                new CreateFeedbackReplyRequestDTO("English reply rendering check."));
        assertNoPlaceholderLeakage(bodyOf(awaitMessage(REPLY_SUBJECTS)));
    }

    private static void assertNoPlaceholderLeakage(String html) {
        assertThat(html)
                .as("rendered mail must not leak format placeholders")
                .doesNotContain("%s")
                .doesNotContain("%d")
                .doesNotContain("null");
    }

    private UUID submitWithLanguage(String category, String body, String language) throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + login(EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"" + category + "\", \"body\": \"" + body
                                + "\", \"context\": {\"language\": \"" + language + "\"}}"))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID submit(String category, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + login(EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"" + category + "\", \"body\": \"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private MimeMessage awaitMessage(List<String> subjectFragments) {
        return await().atMost(Duration.ofSeconds(10))
                .until(() -> messagesMatching(subjectFragments).stream().findFirst(),
                        Optional::isPresent)
                .orElseThrow();
    }

    private List<MimeMessage> messagesMatching(List<String> subjectFragments) {
        return capturedMessages().stream()
                .filter(message -> {
                    String subject = subjectOf(message).toLowerCase();
                    return subjectFragments.stream().anyMatch(subject::contains);
                })
                .toList();
    }

    private List<MimeMessage> capturedMessages() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    private static String subjectOf(MimeMessage message) {
        try {
            String subject = message.getSubject();
            return subject == null ? "" : subject;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the mail subject", e);
        }
    }

    /** The helper builds a multipart message, so the HTML lives in a nested part. */
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
        } else {
            text.append(content);
        }
    }

    private static List<String> recipientsOf(MimeMessage message) {
        try {
            return Arrays.stream(message.getRecipients(Message.RecipientType.TO))
                    .map(Object::toString)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the mail recipients", e);
        }
    }

    private User recreateUser(String email, String name, String language) {
        userRepository.findByEmail(email).ifPresent(existing -> {
            feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId())
                    .forEach(feedback -> feedbackReplyRepository.deleteAll(
                            feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedback.getId())));
            feedbackRepository.deleteAll(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId()));
            refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(existing.getId()));
            userRepository.delete(existing);
        });
        userService.registerUser(new UserRegisterDTO(name, email, PASSWORD, null));

        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user.setLanguageInUse(language);
        return userRepository.save(user);
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

    @Test
    @DisplayName("an account that never set a language is acknowledged in the language it was browsing in")
    void acknowledgementFallsBackToTheSubmissionContextLanguage() throws Exception {
        // languageInUse stays null until the user visits the configuration
        // screen, so reading it first sent every new account an English
        // receipt no matter which language the interface was showing.
        author.setLanguageInUse(null);
        userRepository.saveAndFlush(author);

        submitWithLanguage("BUG", "Enviado com a interface em portugues.", "pt");

        MimeMessage ack = awaitMessage(ACK_SUBJECTS);

        assertThat(subjectOf(ack)).containsIgnoringCase("recebemos seu feedback");
    }

    @Test
    @DisplayName("a stored preference outranks the language the submission was written in")
    void replyPrefersTheStoredPreferenceOverTheSubmissionContext() throws Exception {
        author.setLanguageInUse("pt");
        userRepository.saveAndFlush(author);

        UUID feedbackId = submitWithLanguage("BUG", "Written while the interface was in English.", "en");
        awaitMessage(ACK_SUBJECTS);

        feedbackReplyService.reply(feedbackId, admin.getId(),
                new CreateFeedbackReplyRequestDTO("Ja esta corrigido."));

        MimeMessage reply = awaitMessage(REPLY_SUBJECTS);

        assertThat(subjectOf(reply)).containsIgnoringCase("respondemos seu feedback");
    }
}
