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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U3 — attachments over HTTP: several images per submission (R3), a screenshot
 * accepted alongside the error text a client captured (R9), and bytes served
 * only to the submission's owner or an admin.
 *
 * Runs through the real security filter chain.
 */
@AutoConfigureMockMvc
class FeedbackAttachmentIntegrationTest extends AbstractIntegrationTest {

    private static final String OWNER_EMAIL = "attachment-owner@beyou.test";
    private static final String STRANGER_EMAIL = "attachment-stranger@beyou.test";
    private static final String ADMIN_EMAIL = "attachment-admin@beyou.test";
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

    @Autowired
    FeedbackAttachmentRepository attachmentRepository;

    @BeforeEach
    void setUp() {
        recreateUser(OWNER_EMAIL, "attachment owner", UserRole.USER);
        recreateUser(STRANGER_EMAIL, "attachment stranger", UserRole.USER);
        recreateUser(ADMIN_EMAIL, "attachment admin", UserRole.ADMIN);
    }

    @AfterEach
    void tearDown() {
        // Feedback rows are asserted to be absent by sibling test classes — this
        // class must not leave any of its own behind.
        deleteUser(OWNER_EMAIL);
        deleteUser(STRANGER_EMAIL);
        deleteUser(ADMIN_EMAIL);
    }

    @Test
    @DisplayName("an uploaded image is stored against the submission and served back to its owner")
    void uploadedImageIsStoredAndServedBackToItsOwner() throws Exception {
        String token = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(token);

        MvcResult upload = mockMvc.perform(multipart("/feedback/{id}/attachments", feedbackId)
                        .file(screenshot(1170, 2532))
                        .header("authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feedbackId").value(feedbackId.toString()))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                // R3/R9: stored large enough that interface text in a screenshot survives.
                .andExpect(jsonPath("$.height").value(1920))
                .andReturn();

        UUID attachmentId = UUID.fromString(JsonPath.read(upload.getResponse().getContentAsString(), "$.id"));
        String url = JsonPath.read(upload.getResponse().getContentAsString(), "$.url");
        assertThat(url).isEqualTo("/feedback/" + feedbackId + "/attachments/" + attachmentId);

        assertThat(attachmentRepository.countByFeedbackId(feedbackId)).isEqualTo(1);

        MvcResult served = mockMvc.perform(get(url).header("authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_JPEG_VALUE))
                .andReturn();

        byte[] bytes = served.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(bytes)))
                .as("the served bytes decode as an image")
                .isNotNull();
    }

    @Test
    @DisplayName("several images can be attached to one submission")
    void severalImagesCanBeAttachedToOneSubmission() throws Exception {
        String token = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(token);

        for (int i = 0; i < 3; i++) {
            attach(feedbackId, token).andExpect(status().isCreated());
        }

        assertThat(attachmentRepository.countByFeedbackId(feedbackId)).isEqualTo(3);
    }

    @Test
    @DisplayName("attachments beyond the per-submission cap are rejected")
    void attachmentsBeyondThePerSubmissionCapAreRejected() throws Exception {
        String token = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(token);

        for (int i = 0; i < FeedbackAttachmentService.MAX_ATTACHMENTS_PER_FEEDBACK; i++) {
            attach(feedbackId, token).andExpect(status().isCreated());
        }

        attach(feedbackId, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("FEEDBACK_ATTACHMENT_LIMIT_REACHED"));

        assertThat(attachmentRepository.countByFeedbackId(feedbackId))
                .isEqualTo(FeedbackAttachmentService.MAX_ATTACHMENTS_PER_FEEDBACK);
    }

    @Test
    @DisplayName("a disallowed MIME type is rejected at the endpoint")
    void disallowedMimeTypeIsRejectedAtTheEndpoint() throws Exception {
        String token = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(token);

        mockMvc.perform(multipart("/feedback/{id}/attachments", feedbackId)
                        .file(new MockMultipartFile("file", "notes.pdf", "application/pdf", "%PDF-1.7".getBytes()))
                        .header("authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("FEEDBACK_ATTACHMENT_INVALID_TYPE"));

        assertThat(attachmentRepository.countByFeedbackId(feedbackId)).isZero();
    }

    @Test
    @DisplayName("a non-owner, non-admin request for an attachment is refused")
    void nonOwnerNonAdminRequestIsRefused() throws Exception {
        String ownerToken = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(ownerToken);
        UUID attachmentId = attachAndReturnId(feedbackId, ownerToken);

        MvcResult response = mockMvc.perform(
                        get("/feedback/{f}/attachments/{a}", feedbackId, attachmentId)
                                .header("authorization", "Bearer " + login(STRANGER_EMAIL)))
                .andReturn();

        assertThat(response.getResponse().getStatus())
                .as("a stranger must not be served another user's attachment")
                .isNotEqualTo(200);
        assertThat(response.getResponse().getContentAsString())
                .contains("FEEDBACK_NOT_OWNED");
    }

    @Test
    @DisplayName("a non-owner cannot attach to someone else's submission")
    void nonOwnerCannotAttachToSomeoneElsesSubmission() throws Exception {
        UUID feedbackId = submitFeedback(login(OWNER_EMAIL));

        attach(feedbackId, login(STRANGER_EMAIL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("FEEDBACK_NOT_OWNED"));

        assertThat(attachmentRepository.countByFeedbackId(feedbackId)).isZero();
    }

    @Test
    @DisplayName("an admin can fetch any user's attachment")
    void adminCanFetchAnyUsersAttachment() throws Exception {
        String ownerToken = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(ownerToken);
        UUID attachmentId = attachAndReturnId(feedbackId, ownerToken);

        mockMvc.perform(get("/feedback/{f}/attachments/{a}", feedbackId, attachmentId)
                        .header("authorization", "Bearer " + login(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_JPEG_VALUE));
    }

    // -- helpers --

    private org.springframework.test.web.servlet.ResultActions attach(UUID feedbackId, String token)
            throws Exception {
        return mockMvc.perform(multipart("/feedback/{id}/attachments", feedbackId)
                .file(screenshot(320, 240))
                .header("authorization", "Bearer " + token));
    }

    private UUID attachAndReturnId(UUID feedbackId, String token) throws Exception {
        MvcResult result = attach(feedbackId, token).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID submitFeedback(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/feedback")
                        .header("authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "category": "BUG", "body": "The dashboard blew up, screenshot attached." }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private static MockMultipartFile screenshot(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
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
            feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId())
                    .forEach(feedback -> attachmentRepository.deleteAll(
                            attachmentRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedback.getId())));
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
