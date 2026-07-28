package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3 — the five-attachment cap has to hold when uploads arrive at once.
 *
 * The cap is a count followed by an insert. Read-then-insert with nothing
 * between them is not a limit: two requests that both read four can both write
 * a fifth, and the submission ends up over the cap with no error anywhere. The
 * upload path therefore takes a pessimistic write lock on the submission row,
 * so the count each request reads is the count it inserts against.
 *
 * Deliberately drives the service directly rather than the HTTP endpoint: the
 * race is between transactions, and MockMvc gives no honest way to have two in
 * flight at the same moment.
 */
class FeedbackAttachmentCapConcurrencyTest extends AbstractIntegrationTest {

    private static final int CONCURRENT_UPLOADS = 4;
    /** Leaves exactly one slot free, so every extra upload that lands is a breach. */
    private static final int PRE_EXISTING = FeedbackAttachmentService.MAX_ATTACHMENTS_PER_FEEDBACK - 1;

    @Autowired
    FeedbackAttachmentService attachmentService;

    @Autowired
    FeedbackService feedbackService;

    @Autowired
    FeedbackRepository feedbackRepository;

    @Autowired
    FeedbackAttachmentRepository attachmentRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Value("${app.upload-dir}")
    String uploadDir;

    private User uploader;
    private UUID feedbackId;

    @BeforeEach
    void setUp() {
        cleanUp();

        User user = new User();
        user.setName("attachment race");
        user.setEmail("attachment-race@beyou.test");
        user.setPassword("placeholder");
        user.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        uploader = userRepository.saveAndFlush(user);

        feedbackId = feedbackService.submitFeedback(
                new CreateFeedbackRequestDTO(FeedbackCategory.BUG,
                        "Racing uploads against the per-submission cap.", null),
                uploader.getId()).id();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("concurrent uploads cannot push a submission past the per-submission cap")
    void concurrentUploadsCannotExceedTheCap() throws Exception {
        for (int i = 0; i < PRE_EXISTING; i++) {
            attachmentService.addAttachment(feedbackId, uploader, screenshot());
        }
        assertThat(attachmentRepository.countByFeedbackId(feedbackId)).isEqualTo(PRE_EXISTING);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_UPLOADS);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_UPLOADS);
        try {
            for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        attachmentService.addAttachment(feedbackId, uploader, screenshot());
                        accepted.incrementAndGet();
                    } catch (BusinessException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        // Anything else is a genuine failure; the count assertions below expose it.
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("every concurrent upload has to finish")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(attachmentRepository.countByFeedbackId(feedbackId))
                .as("the cap must hold no matter how the uploads interleave "
                        + "(accepted=%d, rejected=%d)", accepted.get(), rejected.get())
                .isEqualTo(FeedbackAttachmentService.MAX_ATTACHMENTS_PER_FEEDBACK);

        assertThat(accepted.get())
                .as("exactly one of the racing uploads may take the last slot")
                .isEqualTo(1);
    }

    // -- helpers --

    private static MockMultipartFile screenshot() throws IOException {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "screenshot.png", "image/png", out.toByteArray());
    }

    private void cleanUp() {
        userRepository.findByEmail("attachment-race@beyou.test").ifPresent(existing -> {
            List<Feedback> submissions =
                    feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(existing.getId());
            submissions.forEach(feedback -> {
                attachmentRepository.deleteAll(
                        attachmentRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedback.getId()));
                deleteAttachmentDirectory(feedback.getId());
            });
            feedbackRepository.deleteAll(submissions);
            refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(existing.getId()));
            userRepository.delete(existing);
        });
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
