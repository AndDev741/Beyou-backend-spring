package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAttachmentDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Ownership, the per-submission cap, and the index row for attachments (R3, R9).
 * The bytes themselves are {@link FeedbackAttachmentStorageService}'s business.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeedbackAttachmentService {

    /**
     * Cap per submission. A report started from an error state carries one
     * automatic screenshot (R9); the rest is room for the user to add what the
     * capture missed. Beyond a handful the marginal triage value is nil and the
     * storage cost is not.
     */
    public static final int MAX_ATTACHMENTS_PER_FEEDBACK = 5;

    private final FeedbackRepository feedbackRepository;
    private final FeedbackAttachmentRepository attachmentRepository;
    private final FeedbackAttachmentStorageService storageService;
    private final FeedbackMapper feedbackMapper;

    /**
     * Validates and stores one image against a submission the caller owns.
     *
     * Ordering matters: the image is validated and encoded in memory first, so
     * the row can be written with its real dimensions, and only then do the
     * bytes hit the disk. A write failure rolls the row back with the
     * transaction; a save failure never leaves an orphan file, because nothing
     * has been written yet.
     */
    @Transactional
    public FeedbackAttachmentDTO addAttachment(UUID feedbackId, User requester, MultipartFile file) {
        // Uploading is the submitter's act, not a triage action: an admin has no
        // business adding images to somebody else's report.
        Feedback feedback = requireFeedback(feedbackId, requester, false);

        long existing = attachmentRepository.countByFeedbackId(feedbackId);
        if (existing >= MAX_ATTACHMENTS_PER_FEEDBACK) {
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_LIMIT_REACHED,
                    "A submission can carry at most " + MAX_ATTACHMENTS_PER_FEEDBACK + " attachments");
        }

        FeedbackAttachmentStorageService.EncodedAttachment encoded = storageService.validateAndEncode(file);

        FeedbackAttachment attachment = new FeedbackAttachment();
        attachment.setFeedback(feedback);
        attachment.setWidth(encoded.width());
        attachment.setHeight(encoded.height());
        attachment.setSizeBytes(encoded.sizeBytes());

        FeedbackAttachment saved = attachmentRepository.saveAndFlush(attachment);
        storageService.write(feedbackId, saved.getId(), encoded);

        log.info("Attachment {} added to feedback {} by user {}", saved.getId(), feedbackId, requester.getId());
        return toDTO(saved, feedbackId);
    }

    /**
     * Serves an attachment to the submission's owner or to an admin, and to
     * nobody else. {@code /feedback/admin/**} is gated to ROLE_ADMIN by
     * SecurityConfig, but this route is an ordinary authenticated one — the
     * ownership check has to happen here.
     */
    @Transactional(readOnly = true)
    public Resource serveAttachment(UUID feedbackId, UUID attachmentId, User requester) {
        requireFeedback(feedbackId, requester, true);

        FeedbackAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_NOT_FOUND,
                        "Attachment not found"));

        if (!attachment.getFeedback().getId().equals(feedbackId)) {
            // The id pair has to be consistent, or an owner of submission A
            // could read an attachment belonging to submission B.
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_NOT_FOUND,
                    "Attachment not found on this submission");
        }

        Resource resource = storageService.serve(feedbackId, attachmentId);
        if (resource == null) {
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_NOT_FOUND,
                    "Attachment bytes are missing");
        }
        return resource;
    }

    private Feedback requireFeedback(UUID feedbackId, User requester, boolean adminMayAct) {
        Feedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new BusinessException(ErrorKey.FEEDBACK_NOT_FOUND, "Feedback not found"));

        boolean isOwner = feedback.getUser().getId().equals(requester.getId());
        if (isOwner || (adminMayAct && requester.getUserRole() == UserRole.ADMIN)) {
            return feedback;
        }
        throw new BusinessException(ErrorKey.FEEDBACK_NOT_OWNED, "This feedback belongs to another user");
    }

    /**
     * Delegated so the served-bytes URL is built in exactly one place — the
     * admin detail view (U5) renders the same attachments and must not grow a
     * second copy of this path that can drift from the serving route.
     */
    private FeedbackAttachmentDTO toDTO(FeedbackAttachment attachment, UUID feedbackId) {
        return feedbackMapper.toAttachmentDTO(attachment, feedbackId);
    }
}
