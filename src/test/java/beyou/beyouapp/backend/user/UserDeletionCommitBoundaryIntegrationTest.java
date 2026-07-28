package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentService;
import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackRepository;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.security.passwordreset.PasswordResetToken;
import beyou.beyouapp.backend.security.passwordreset.PasswordResetTokenRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.UnexpectedRollbackException;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * R21 — the attachment purge must run on the far side of the COMMIT, not merely
 * on the far side of the flush.
 *
 * <p>{@code UserDeletionOrderingUnitTest} pins the ORDER of the steps, and that
 * is all it can pin: it drives a hand-built {@code UserService} with mocks, so
 * there is no transaction manager, no commit, and nothing that could tell a
 * purge performed inline apart from a purge deferred to the commit callback.
 * Between the flush and the commit the delete can still be rolled back — a
 * deferred constraint, a dropped connection, a rollback marked by an outer
 * transaction — and a purge inside that window destroys the files of an account
 * that then survives.
 *
 * <p>This test has a real boundary, so it asks the only question that settles
 * it: <b>at the moment the purge runs, is the account row already gone as seen
 * from a connection that is not in this transaction?</b> A second connection
 * under Postgres READ COMMITTED still sees a row whose DELETE is flushed but
 * uncommitted, and stops seeing it the instant the commit lands. So the answer
 * is "yes" only if the purge really is post-commit.
 *
 * <p>Deliberately NOT {@code @Transactional}: a test-managed transaction that
 * gets rolled back at the end never commits, and the callback under test would
 * never fire at all.
 */
class UserDeletionCommitBoundaryIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "deletion-commit-boundary@beyou.test";

    /**
     * Spied, not mocked: the purge has to really delete the files (the last
     * assertion checks the disk), but the call has to be observed at the exact
     * moment it happens, while the answer to "has the commit landed?" is still
     * available.
     */
    @MockitoSpyBean
    FeedbackAttachmentService attachmentService;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    FeedbackService feedbackService;

    @Autowired
    FeedbackRepository feedbackRepository;

    @Autowired
    PasswordResetTokenRepository passwordResetTokenRepository;

    @Value("${app.upload-dir}")
    String uploadDir;

    @Value("${spring.datasource.url}")
    String jdbcUrl;

    @Value("${spring.datasource.username}")
    String jdbcUsername;

    @Value("${spring.datasource.password}")
    String jdbcPassword;

    private User user;
    private UUID feedbackId;
    private UUID blockerTokenId;
    private Path attachmentDir;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();

        User fresh = new User();
        fresh.setName("commit boundary");
        fresh.setEmail(EMAIL);
        fresh.setPassword("placeholder");
        fresh.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        user = userRepository.saveAndFlush(fresh);

        feedbackId = feedbackService.submitFeedback(
                new CreateFeedbackRequestDTO(FeedbackCategory.BUG,
                        "A submission whose screenshot must outlive a failed delete.", null),
                user.getId()).id();
        attachmentService.addAttachment(feedbackId, user, screenshot());

        attachmentDir = Path.of(uploadDir).resolve("feedback-attachments").resolve(feedbackId.toString());
        assertThat(Files.exists(attachmentDir))
                .as("the fixture is only meaningful if there are real bytes to lose")
                .isTrue();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("the attachment purge runs only after the delete has COMMITTED, not merely after it flushed")
    void purgeRunsOnlyAfterTheDeleteCommits() {
        // Recorded from inside the purge call itself: whether an independent
        // connection could still see the account at that moment.
        AtomicReference<Boolean> accountStillVisibleAtPurgeTime = new AtomicReference<>();

        doAnswer(invocation -> {
            accountStillVisibleAtPurgeTime.set(existsOnAnIndependentConnection(user.getId()));
            return invocation.callRealMethod();
        }).when(attachmentService).purgeStoredFiles(anyCollection());

        var response = userService.deleteUser(user);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(attachmentService).purgeStoredFiles(List.of(feedbackId));

        assertThat(accountStillVisibleAtPurgeTime.get())
                .as("the purge ran while the DELETE was still uncommitted — a rollback in that "
                        + "window would leave the account standing with its screenshots destroyed")
                .isFalse();

        assertThat(existsOnAnIndependentConnection(user.getId()))
                .as("and the delete did land")
                .isFalse();
        assertThat(Files.exists(attachmentDir))
                .as("the deferred purge still has to actually remove the files")
                .isFalse();
    }

    /**
     * The mirror image, with a real proxy and a real foreign key: a delete that
     * cannot succeed must cost no files.
     *
     * <p>{@code password_reset_tokens.user_id} is a plain non-cascading foreign
     * key (V1__baseline.sql) that {@code User} maps no {@code @OneToMany} for and
     * {@code deleteUser} does not clear, so a row there blocks the delete for
     * real rather than through a mock.
     *
     * <p>Also the honest counterpart to the assertion
     * {@code UserDeletionOrderingUnitTest} used to make. The flush failure marks
     * the transaction rollback-only, so the caller never receives the 400 the
     * method's own catch block builds: the proxy raises
     * {@code UnexpectedRollbackException} on the way out, and
     * {@code GlobalExceptionHandler} has no handler for it, so a client sees a
     * 500. A mock harness has no proxy and therefore cannot produce that.
     */
    @Test
    @DisplayName("a delete blocked by a real foreign key rolls back past the method's 400 and purges nothing")
    void blockedDeleteLeavesTheFilesOnDisk() {
        PasswordResetToken blocker = new PasswordResetToken();
        blocker.setUser(user);
        blocker.setTokenHash("blocks-the-delete");
        blocker.setCreatedAt(Timestamp.from(Instant.now()));
        blocker.setExpiresAt(Timestamp.from(Instant.now().plusSeconds(900)));
        blockerTokenId = passwordResetTokenRepository.saveAndFlush(blocker).getId();

        assertThatThrownBy(() -> userService.deleteUser(user))
                .as("the method returns a 400 body, but the transaction is already rollback-only, "
                        + "so the proxy — not the method — decides what the caller sees")
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(attachmentService, never()).purgeStoredFiles(anyCollection());
        assertThat(Files.exists(attachmentDir))
                .as("a delete that could not happen must not have cost the account its screenshots")
                .isTrue();
        assertThat(existsOnAnIndependentConnection(user.getId()))
                .as("and the account is still there")
                .isTrue();
    }

    // -- helpers --

    /**
     * Asks a connection outside the application's pool and outside any
     * transaction under test whether the account row is visible. Raw
     * {@link DriverManager} on purpose: the test profile pins the Hikari pool to
     * two connections, and borrowing one of those while a delete transaction
     * holds another is a needless way to make this test depend on pool sizing.
     */
    private boolean existsOnAnIndependentConnection(UUID userId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM users WHERE id = ?")) {
            statement.setObject(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the account row from an independent connection", e);
        }
    }

    private static MockMultipartFile screenshot() throws IOException {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "screenshot.png", "image/png", out.toByteArray());
    }

    private void cleanUp() {
        if (blockerTokenId != null) {
            passwordResetTokenRepository.deleteById(blockerTokenId);
            blockerTokenId = null;
        }
        userRepository.findByEmail(EMAIL).ifPresent(existing -> {
            List<UUID> submissionIds = feedbackRepository.findIdsByUserId(existing.getId());
            feedbackRepository.deleteAll(feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId()));
            userRepository.delete(existing);
            submissionIds.forEach(this::deleteAttachmentDirectory);
        });
        if (feedbackId != null) {
            deleteAttachmentDirectory(feedbackId);
            feedbackId = null;
        }
    }

    private void deleteAttachmentDirectory(UUID id) {
        Path dir = Path.of(uploadDir).resolve("feedback-attachments").resolve(id.toString());
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
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
}
