package beyou.beyouapp.backend.unit.feedback;

import beyou.beyouapp.backend.domain.feedback.Feedback;
import beyou.beyouapp.backend.domain.feedback.FeedbackAttachment;
import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentRepository;
import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentService;
import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentStorageService;
import beyou.beyouapp.backend.domain.feedback.FeedbackMapper;
import beyou.beyouapp.backend.domain.feedback.FeedbackRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R3 — HOW WIDE the attachment cap's write lock is, which is a separate question
 * from whether it holds.
 *
 * <p>{@code FeedbackAttachmentCapConcurrencyTest} proves the cap survives
 * concurrent uploads. It would go on proving that if the lock were taken at the
 * top of the method and held across the image decode and the disk write — the cap
 * would still hold, and every concurrent upload to the same submission would
 * queue behind a CPU-bound resample of up to 25 megapixels. Correct and slow
 * looks identical from there.
 *
 * <p>So this pins the boundary directly: the decode happens BEFORE the locking
 * read, and the lock covers the count, the insert and the (bounded) write.
 * Mockito rather than Spring because the claim is about call order, and call
 * order is exactly what a mock can see and a real transaction cannot.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackAttachmentLockScopeUnitTest {

    @Mock
    FeedbackRepository feedbackRepository;

    @Mock
    FeedbackAttachmentRepository attachmentRepository;

    @Mock
    FeedbackAttachmentStorageService storageService;

    @Mock
    FeedbackMapper feedbackMapper;

    private FeedbackAttachmentService service;

    private final UUID feedbackId = UUID.randomUUID();
    private final UUID attachmentId = UUID.randomUUID();
    private final User owner = new User();
    private final Feedback feedback = new Feedback();
    private final MultipartFile file =
            new MockMultipartFile("file", "screenshot.png", "image/png", new byte[] {1, 2, 3});
    private final FeedbackAttachmentStorageService.EncodedAttachment encoded =
            new FeedbackAttachmentStorageService.EncodedAttachment(new byte[] {4, 5, 6}, 320, 240);

    @BeforeEach
    void setUp() {
        owner.setId(UUID.randomUUID());
        owner.setEmail("lock-scope@beyou.test");
        feedback.setId(feedbackId);
        feedback.setUser(owner);
        service = new FeedbackAttachmentService(
                feedbackRepository, attachmentRepository, storageService, feedbackMapper);
    }

    @Test
    @DisplayName("the image is decoded before the write lock is taken, and the lock covers the count, the insert and the write")
    void theLockCoversTheCountAndInsertAndNotTheDecode() {
        when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(feedback));
        when(storageService.validateAndEncode(file)).thenReturn(encoded);
        when(feedbackRepository.findByIdForUpdate(feedbackId)).thenReturn(Optional.of(feedback));
        when(attachmentRepository.countByFeedbackId(feedbackId)).thenReturn(0L);
        when(attachmentRepository.saveAndFlush(any(FeedbackAttachment.class))).thenAnswer(invocation -> {
            FeedbackAttachment saved = invocation.getArgument(0);
            saved.setId(attachmentId);
            return saved;
        });

        service.addAttachment(feedbackId, owner, file);

        InOrder order = inOrder(feedbackRepository, storageService, attachmentRepository);
        // Authorization, unlocked: a request for somebody else's submission must
        // not be able to buy itself a decode.
        order.verify(feedbackRepository).findById(feedbackId);
        // The expensive, shared-state-free part, still unlocked.
        order.verify(storageService).validateAndEncode(file);
        // Only NOW is the row locked. Moving this call above validateAndEncode is
        // the regression this test exists to fail on.
        order.verify(feedbackRepository).findByIdForUpdate(feedbackId);
        order.verify(attachmentRepository).countByFeedbackId(feedbackId);
        order.verify(attachmentRepository).saveAndFlush(any(FeedbackAttachment.class));
        // Inside the lock on purpose: the write has to stay in the transaction so
        // a failure rolls the row back, and by here the payload is a downscaled
        // JPEG rather than whatever arrived on the wire.
        order.verify(storageService).write(eq(feedbackId), eq(attachmentId), same(encoded));
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("an upload aimed at somebody else's submission is refused before anything is decoded or locked")
    void anotherUsersSubmissionIsRefusedBeforeTheDecode() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        stranger.setEmail("stranger@beyou.test");
        when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(feedback));

        assertThatThrownBy(() -> service.addAttachment(feedbackId, stranger, file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.FEEDBACK_NOT_OWNED);

        verifyNoInteractions(storageService, attachmentRepository);
        verify(feedbackRepository, never()).findByIdForUpdate(any());
    }
}
