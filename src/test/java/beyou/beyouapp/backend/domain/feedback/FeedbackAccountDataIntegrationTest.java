package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatRepository;
import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckHistoryService;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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

    @Autowired
    ChatRepository chatRepository;

    @Autowired
    EntityCheckDayRepository entityCheckDayRepository;

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

    /**
     * R10 — the day history joins the export, grouped by the owner it describes, and
     * carrying every owner type rather than only habits.
     */
    @Test
    @DisplayName("the export carries the check-day history for every owner type — and nobody else's")
    void exportCarriesTheCheckDayHistoryForEveryOwnerTypeAndNobodyElses() throws Exception {
        User owner = userRepository.findByEmail(OWNER_EMAIL).orElseThrow();
        User stranger = userRepository.findByEmail(STRANGER_EMAIL).orElseThrow();
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        UUID habitOwner = UUID.randomUUID();
        UUID taskOwner = UUID.randomUUID();
        UUID routineOwner = UUID.randomUUID();
        UUID strangerOwner = UUID.randomUUID();

        checkDay(owner, CheckDayOwnerType.HABIT, habitOwner, today.minusDays(1), CheckDayOutcome.DONE);
        checkDay(owner, CheckDayOwnerType.HABIT, habitOwner, today, CheckDayOutcome.MISSED);
        checkDay(owner, CheckDayOwnerType.TASK, taskOwner, today, CheckDayOutcome.SKIPPED);
        checkDay(owner, CheckDayOwnerType.ROUTINE, routineOwner, today, CheckDayOutcome.NOT_SCHEDULED);
        checkDay(owner, CheckDayOwnerType.USER, owner.getId(), today, CheckDayOutcome.DONE);
        checkDay(stranger, CheckDayOwnerType.HABIT, strangerOwner, today, CheckDayOutcome.DONE);

        MvcResult result = mockMvc.perform(get("/user/export")
                        .header("authorization", "Bearer " + login(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkHistory.owners.length()").value(4))
                .andExpect(jsonPath("$.checkHistory.owners[?(@.ownerId == '" + habitOwner + "')].ownerType")
                        .value("HABIT"))
                .andExpect(jsonPath("$.checkHistory.owners[?(@.ownerId == '" + habitOwner + "')].days.length()")
                        .value(2))
                .andExpect(jsonPath("$.checkHistory.owners[?(@.ownerId == '" + taskOwner + "')].ownerType")
                        .value("TASK"))
                .andExpect(jsonPath("$.checkHistory.owners[?(@.ownerId == '" + routineOwner + "')].ownerType")
                        .value("ROUTINE"))
                .andExpect(jsonPath("$.checkHistory.owners[?(@.ownerId == '" + owner.getId() + "')].ownerType")
                        .value("USER"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Oldest first within an owner, matching the order the rows are stored in.
        assertThat(JsonPath.<List<String>>read(body,
                "$.checkHistory.owners[?(@.ownerId == '" + habitOwner + "')].days[*].day"))
                .containsExactly(today.minusDays(1).toString(), today.toString());
        assertThat(JsonPath.<List<String>>read(body,
                "$.checkHistory.owners[?(@.ownerId == '" + habitOwner + "')].days[*].outcome"))
                .containsExactly("DONE", "MISSED");
        assertThat(body)
                .as("another account's history must never appear in this export")
                .doesNotContain(strangerOwner.toString());
    }

    /**
     * R10 — the section is bounded, because it is the one part of the export that grows
     * without limit and the whole payload is built in memory. A truncation the reader cannot
     * see would be worse than the truncation itself, so the covered range is in the payload.
     */
    @Test
    @DisplayName("the export bounds the check-day history to the cap and says what it covered")
    void exportBoundsTheCheckDayHistoryAndSaysSo() throws Exception {
        User owner = userRepository.findByEmail(OWNER_EMAIL).orElseThrow();
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        LocalDate oldestCovered = today.minusDays(CheckHistoryService.MAX_RANGE_DAYS - 1L);

        UUID recent = UUID.randomUUID();
        UUID onTheEdge = UUID.randomUUID();
        UUID ancient = UUID.randomUUID();

        checkDay(owner, CheckDayOwnerType.HABIT, recent, today.minusDays(10), CheckDayOutcome.DONE);
        checkDay(owner, CheckDayOwnerType.HABIT, onTheEdge, oldestCovered, CheckDayOutcome.DONE);
        checkDay(owner, CheckDayOwnerType.HABIT, ancient, oldestCovered.minusDays(1), CheckDayOutcome.DONE);

        MvcResult result = mockMvc.perform(get("/user/export")
                        .header("authorization", "Bearer " + login(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkHistory.from").value(oldestCovered.toString()))
                .andExpect(jsonPath("$.checkHistory.to").value(today.toString()))
                .andExpect(jsonPath("$.checkHistory.maxRangeDays")
                        .value(CheckHistoryService.MAX_RANGE_DAYS))
                .andExpect(jsonPath("$.checkHistory.note").isNotEmpty())
                .andExpect(jsonPath("$.checkHistory.owners.length()").value(2))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).as("a day inside the window is exported").contains(recent.toString());
        assertThat(body).as("the oldest covered day is inclusive").contains(onTheEdge.toString());
        assertThat(body)
                .as("a day older than the cap is left out — visibly, via the stated range")
                .doesNotContain(ancient.toString());
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

    /**
     * The realistic case: an account that has logged in holds a refresh token,
     * and {@code refresh_tokens.user_id} is a plain non-cascading foreign key
     * (V1__baseline.sql) that {@code User} maps no {@code @OneToMany} for. The
     * sibling tests above delete those rows by hand first; nothing outside a
     * test ever would, so the deletion path has to clear them itself.
     */
    @Test
    @DisplayName("deleting an account that still holds a refresh token succeeds and takes its attachment files")
    void accountDeletionClearsItsOwnRefreshTokens() throws Exception {
        String ownerToken = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(ownerToken, OWNER_BODY);
        UUID attachmentId = attachAndReturnId(feedbackId, ownerToken);

        User owner = userRepository.findByEmail(OWNER_EMAIL).orElseThrow();
        UUID ownerId = owner.getId();
        assertThat(refreshTokenRepository.findAllByUserId(ownerId))
                .as("logging in leaves a refresh token behind")
                .isNotEmpty();

        Path file = Path.of(uploadDir).resolve("feedback-attachments")
                .resolve(feedbackId.toString()).resolve(attachmentId + ".jpg");
        assertThat(file).exists();

        userService.deleteUser(owner);

        assertThat(userRepository.findByEmail(OWNER_EMAIL))
                .as("the account the user asked to remove must actually be gone")
                .isEmpty();
        assertThat(refreshTokenRepository.findAllByUserId(ownerId)).isEmpty();
        assertThat(file).as("its attachment bytes go with it").doesNotExist();
    }

    /**
     * The counterpart promise: when something DOES block the row delete, the
     * bytes must still be there afterwards. {@code chats.user_id} (V5__chat.sql)
     * is another plain non-cascading foreign key, so a user who has used the
     * agent chat is exactly such a case — and this test also documents that
     * blocker.
     */
    @Test
    @DisplayName("a delete blocked by a foreign key leaves the attachment files on disk")
    void blockedAccountDeletionLeavesTheAttachmentFilesOnDisk() throws Exception {
        String ownerToken = login(OWNER_EMAIL);
        UUID feedbackId = submitFeedback(ownerToken, OWNER_BODY);
        UUID attachmentId = attachAndReturnId(feedbackId, ownerToken);

        User owner = userRepository.findByEmail(OWNER_EMAIL).orElseThrow();
        UUID ownerId = owner.getId();

        Chat blocker = new Chat();
        blocker.setUser(owner);
        blocker.setTitle("blocks the delete");
        chatRepository.saveAndFlush(blocker);

        Path file = Path.of(uploadDir).resolve("feedback-attachments")
                .resolve(feedbackId.toString()).resolve(attachmentId + ".jpg");
        assertThat(file).exists();

        // However it surfaces — a bad-request body or a rollback thrown out of
        // the proxy — what must NOT happen is a destroyed screenshot.
        catchThrowable(() -> userService.deleteUser(owner));

        assertThat(userRepository.findById(ownerId))
                .as("the delete was blocked, so the account is still here")
                .isPresent();
        assertThat(file)
                .as("a blocked delete must not have already destroyed the bytes")
                .exists();

        chatRepository.delete(blocker);
    }

    // -- helpers --

    private void checkDay(User user, CheckDayOwnerType ownerType, UUID ownerId,
                          LocalDate day, CheckDayOutcome outcome) {
        entityCheckDayRepository.saveAndFlush(
                new EntityCheckDay(user, ownerType, ownerId, day, outcome));
    }

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
            chatRepository.deleteAll(chatRepository.findAllByUserIdOrderByUpdatedAtDesc(existing.getId()));
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
