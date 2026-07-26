package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.enums.UserRole;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U6/R21 — the account-data promises, now that feedback exists.
 *
 * Two promises are on trial here. The export has to carry everything the user
 * wrote and everything written back to them, and nobody else's. Deleting the
 * account has to take all of it away — the rows, which the database cascades
 * (V9/V10/V12), and the attachment bytes on disk, which nothing cascades to and
 * would otherwise outlive the account forever.
 */
@AutoConfigureMockMvc
class FeedbackAccountDataIntegrationTest extends AbstractIntegrationTest {

    private static final String OWNER_EMAIL = "account-data-owner@beyou.test";
    private static final String STRANGER_EMAIL = "account-data-stranger@beyou.test";
    private static final String ADMIN_EMAIL = "account-data-admin@beyou.test";
    private static final String PASSWORD = "TestPassword1!";

    private static final String OWNER_BODY = "The routine screen forgets my check-ins.";
    private static final String STRANGER_BODY = "STRANGER-ONLY-TEXT: my goals never complete.";
    private static final String REPLY_BODY = "Thanks — fixed in the next release.";

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
    FeedbackAttachmentRepository attachmentRepository;

    @Autowired
    FeedbackReplyRepository replyRepository;

    @Value("${app.upload-dir}")
    String uploadDir;

    @BeforeEach
    void setUp() {
        recreateUser(OWNER_EMAIL, "account data owner", UserRole.USER);
        recreateUser(STRANGER_EMAIL, "account data stranger", UserRole.USER);
        recreateUser(ADMIN_EMAIL, "account data admin", UserRole.ADMIN);
    }

    @AfterEach
    void tearDown() {
        deleteUser(OWNER_EMAIL);
        deleteUser(STRANGER_EMAIL);
        deleteUser(ADMIN_EMAIL);
    }

    @Test
    @DisplayName("the export carries the user's submissions, replies and attachment references — and nobody else's")
    void exportCarriesSubmissionsRepliesAndAttachmentsAndNobodyElses() throws Exception {
        String ownerToken = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(ownerToken, OWNER_BODY);
        UUID attachmentId = attachAndReturnId(feedbackId, ownerToken);
        reply(feedbackId, login(ADMIN_EMAIL));

        // A second user's submission that must not leak into the owner's export.
        submitFeedback(login(STRANGER_EMAIL), STRANGER_BODY);

        MvcResult result = mockMvc.perform(get("/user/export")
                        .header("authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedback").isArray())
                .andExpect(jsonPath("$.feedback.length()").value(1))
                .andExpect(jsonPath("$.feedback[0].id").value(feedbackId.toString()))
                .andExpect(jsonPath("$.feedback[0].category").value("BUG"))
                .andExpect(jsonPath("$.feedback[0].body").value(OWNER_BODY))
                .andExpect(jsonPath("$.feedback[0].attachments.length()").value(1))
                .andExpect(jsonPath("$.feedback[0].attachments[0].id").value(attachmentId.toString()))
                .andExpect(jsonPath("$.feedback[0].attachments[0].url")
                        .value("/feedback/" + feedbackId + "/attachments/" + attachmentId))
                .andExpect(jsonPath("$.feedback[0].replies.length()").value(1))
                .andExpect(jsonPath("$.feedback[0].replies[0].body").value(REPLY_BODY))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("another user's submission must never appear in this export")
                .doesNotContain(STRANGER_BODY);
    }

    @Test
    @DisplayName("deleting the account removes the feedback, attachment and reply rows")
    void accountDeletionRemovesTheRows() throws Exception {
        String ownerToken = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(ownerToken, OWNER_BODY);
        attachAndReturnId(feedbackId, ownerToken);
        reply(feedbackId, login(ADMIN_EMAIL));

        User owner = userRepository.findByEmail(OWNER_EMAIL).orElseThrow();
        UUID ownerId = owner.getId();

        assertThat(attachmentRepository.countByFeedbackId(feedbackId)).isEqualTo(1);
        assertThat(replyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedbackId)).hasSize(1);

        refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(ownerId));
        userService.deleteUser(owner);

        assertThat(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(ownerId)).isEmpty();
        assertThat(feedbackRepository.findById(feedbackId)).isEmpty();
        assertThat(attachmentRepository.countByFeedbackId(feedbackId)).isZero();
        assertThat(replyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedbackId)).isEmpty();
    }

    @Test
    @DisplayName("deleting the account removes the attachment files from disk, leaving no orphans")
    void accountDeletionRemovesTheAttachmentFilesFromDisk() throws Exception {
        String ownerToken = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(ownerToken, OWNER_BODY);
        UUID first = attachAndReturnId(feedbackId, ownerToken);
        UUID second = attachAndReturnId(feedbackId, ownerToken);

        Path dir = Path.of(uploadDir).resolve("feedback-attachments").resolve(feedbackId.toString());
        Path firstFile = dir.resolve(first + ".jpg");
        Path secondFile = dir.resolve(second + ".jpg");

        assertThat(firstFile).as("the upload wrote its bytes").exists();
        assertThat(secondFile).as("the upload wrote its bytes").exists();

        User owner = userRepository.findByEmail(OWNER_EMAIL).orElseThrow();
        refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(owner.getId()));
        userService.deleteUser(owner);

        assertThat(firstFile).as("no attachment file may outlive the account").doesNotExist();
        assertThat(secondFile).as("no attachment file may outlive the account").doesNotExist();
        assertThat(dir).as("the submission's directory must not be left behind either").doesNotExist();
    }

    // -- helpers --

    private UUID submitFeedback(String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"category\": \"BUG\", \"body\": \"" + body + "\" }"))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID attachAndReturnId(UUID feedbackId, String token) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/feedback/{id}/attachments", feedbackId)
                        .file(screenshot())
                        .header("authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private void reply(UUID feedbackId, String adminToken) throws Exception {
        mockMvc.perform(post("/feedback/admin/items/{id}/replies", feedbackId)
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"body\": \"" + REPLY_BODY + "\" }"))
                .andExpect(status().isCreated());
    }

    private static MockMultipartFile screenshot() throws IOException {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "screenshot.png", "image/png", out.toByteArray());
    }

    private User recreateUser(String email, String name, UserRole role) {
        deleteUser(email);
        userService.registerUser(new UserRegisterDTO(name, email, PASSWORD));

        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user.setUserRole(role);
        return userRepository.save(user);
    }

    private void deleteUser(String email) {
        userRepository.findByEmail(email).ifPresent(existing -> {
            List<Feedback> submissions =
                    feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId());
            submissions.forEach(feedback -> {
                replyRepository.deleteAll(
                        replyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedback.getId()));
                attachmentRepository.deleteAll(
                        attachmentRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedback.getId()));
                deleteAttachmentDirectory(feedback.getId());
            });
            feedbackRepository.deleteAll(submissions);
            // Replies this account authored on submissions that still exist.
            replyRepository.findAll().stream()
                    .filter(r -> r.getAuthor() != null && r.getAuthor().getId().equals(existing.getId()))
                    .forEach(replyRepository::delete);
            refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(existing.getId()));
            userRepository.delete(existing);
        });
    }

    private void deleteAttachmentDirectory(UUID feedbackId) {
        Path dir = Path.of(uploadDir).resolve("feedback-attachments").resolve(feedbackId.toString());
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // test cleanup is best-effort
                }
            });
        } catch (IOException ignored) {
            // test cleanup is best-effort
        }
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
