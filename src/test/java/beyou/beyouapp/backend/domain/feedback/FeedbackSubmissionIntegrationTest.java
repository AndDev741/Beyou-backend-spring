package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U2 — a feedback submission is persisted with its category, body, status and
 * captured context.
 *
 * Covers R2 (category from a fixed set), R4 (context captured automatically —
 * the client supplies it, the user never types it), R6 (only category + body
 * are required of the user), R10/KD8 (persisted before any side effect) and
 * R11/KD4 (status is internal: it is stored, but never returned to the
 * submitting user).
 *
 * Runs through the real security filter chain — a submission is authenticated
 * and owned by the caller, not by anything in the request body.
 */
@AutoConfigureMockMvc
class FeedbackSubmissionIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "feedback-author@beyou.test";
    private static final String OTHER_EMAIL = "feedback-stranger@beyou.test";
    private static final String PASSWORD = "TestPassword1!";

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

    private User author;
    private User stranger;

    @BeforeEach
    void setUp() {
        author = recreateUser(EMAIL, "feedback author");
        stranger = recreateUser(OTHER_EMAIL, "feedback stranger");
    }

    @Test
    @DisplayName("a valid submission is persisted with status open and the submitting user as owner")
    void validSubmissionPersistsAsOpenAndOwnedByTheSubmitter() throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + login(EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "BUG",
                                  "body": "The routine check-in button does nothing on my phone."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("BUG"))
                .andExpect(jsonPath("$.body").value("The routine check-in button does nothing on my phone."))
                // R11/KD4: status is an internal triage tool — never shown to the submitter.
                .andExpect(jsonPath("$.status").doesNotExist())
                .andReturn();

        UUID id = idOf(result);
        Feedback saved = feedbackRepository.findById(id).orElseThrow();

        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.OPEN);
        assertThat(saved.getCategory()).isEqualTo(FeedbackCategory.BUG);
        assertThat(saved.getUser().getId()).isEqualTo(author.getId());
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("a submission with no category is rejected at validation")
    void submissionWithoutCategoryIsRejected() throws Exception {
        mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + login(EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "body": "Something is off but I will not say what kind of thing it is." }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));

        assertThat(submissionsOfTheAuthor()).isEmpty();
    }

    @Test
    @DisplayName("a body beyond the allowed length is rejected at validation")
    void oversizedBodyIsRejected() throws Exception {
        String tooLong = "x".repeat(FeedbackService.BODY_MAX + 1);

        mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + login(EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission("OTHER", tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));

        assertThat(submissionsOfTheAuthor()).isEmpty();
    }

    @Test
    @DisplayName("captured context persists and round-trips through the response")
    void capturedContextPersistsAndRoundTrips() throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + login(EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "FEATURE_REQUEST",
                                  "body": "Let me reorder the sections of a routine by dragging them.",
                                  "context": {
                                    "screen": "/routines",
                                    "appVersion": "1.4.2",
                                    "platform": "web",
                                    "language": "pt",
                                    "theme": "beYouDark"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.context.screen").value("/routines"))
                .andExpect(jsonPath("$.context.appVersion").value("1.4.2"))
                .andExpect(jsonPath("$.context.platform").value("web"))
                .andExpect(jsonPath("$.context.language").value("pt"))
                .andExpect(jsonPath("$.context.theme").value("beYouDark"))
                .andReturn();

        UUID id = idOf(result);
        FeedbackContext persisted = feedbackRepository.findById(id).orElseThrow().getContext();

        assertThat(persisted.getScreen()).isEqualTo("/routines");
        assertThat(persisted.getAppVersion()).isEqualTo("1.4.2");
        assertThat(persisted.getPlatform()).isEqualTo("web");
        assertThat(persisted.getLanguage()).isEqualTo("pt");
        assertThat(persisted.getTheme()).isEqualTo("beYouDark");
    }

    @Test
    @DisplayName("a submission without context still succeeds — only category and body are required")
    void contextIsOptional() throws Exception {
        mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + login(EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "category": "OTHER", "body": "Thanks for building this." }
                                """))
                .andExpect(status().isCreated());

        assertThat(submissionsOfTheAuthor()).hasSize(1);
    }

    @Test
    @DisplayName("a user cannot read another user's submission through any non-admin route")
    void aStrangerCannotReadSomeoneElsesSubmission() throws Exception {
        String secret = "My password reset mail never arrives, here is my private detail.";
        MvcResult created = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + login(EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission("BUG", secret)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID id = idOf(created);
        String strangerToken = login(OTHER_EMAIL);

        // The submission is owned by the author…
        assertThat(feedbackRepository.findById(id).orElseThrow().getUser().getId())
                .isEqualTo(author.getId())
                .isNotEqualTo(stranger.getId());

        // …and no non-admin route hands it to anybody else.
        for (String route : new String[]{"/feedback/" + id, "/feedback", "/feedback/items"}) {
            MvcResult response = mockMvc.perform(get(route)
                            .header("authorization", "Bearer " + strangerToken))
                    .andReturn();

            assertThat(response.getResponse().getStatus())
                    .as("non-admin GET %s must not serve another user's submission", route)
                    .isNotEqualTo(200);
            assertThat(response.getResponse().getContentAsString())
                    .as("non-admin GET %s must not leak submission content", route)
                    .doesNotContain(secret);
        }
    }

    /** Minimal JSON body — every value used here is plain text needing no escaping. */
    private static String submission(String category, String body) {
        return "{\"category\": \"" + category + "\", \"body\": \"" + body + "\"}";
    }

    /**
     * This author's submissions, never the whole table.
     *
     * These three cases are about what THIS request stored, and `findAll()` answered a
     * wider question: whether anybody's row exists. The feedback package's other
     * integration classes use their own users and leave their rows behind for the
     * lifetime of the JVM, so "the table is empty" holds or fails on the order
     * Surefire happens to pick — which is what turned green into red the moment a new
     * class joined the package.
     */
    private List<Feedback> submissionsOfTheAuthor() {
        return feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(author.getId());
    }

    private static UUID idOf(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private User recreateUser(String email, String name) {
        userRepository.findByEmail(email).ifPresent(existing -> {
            feedbackRepository.deleteAll(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId()));
            refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(existing.getId()));
            userRepository.delete(existing);
        });
        userService.registerUser(new UserRegisterDTO(name, email, PASSWORD));

        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
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
}
