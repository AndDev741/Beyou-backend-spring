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

import java.util.Collection;
import java.util.List;
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
     *
     * The submission row is locked for the duration. The cap is a count
     * followed by an insert, and read-then-insert with nothing in between is
     * not a cap at all: two uploads that both read four both write a fifth, and
     * the submission ends up over the limit with no error raised anywhere. The
     * lock makes the count each request reads the count it inserts against.
     */
    @Transactional
    public FeedbackAttachmentDTO addAttachment(UUID feedbackId, User requester, MultipartFile file) {
        // Uploading is the submitter's act, not a triage action: an admin has no
        // business adding images to somebody else's report.
        Feedback feedback = requireFeedback(feedbackId, requester, false, true);

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

    /**
     * R21 — the ids of every submission this account made, for the deletion path.
     *
     * Attachment files are addressed by feedback id and nothing in the database
     * reaches them, so the ids have to be read while the submissions still
     * exist — the cascade (V9/V10) takes those rows away with the user. Reading
     * them is all this does; see {@link #purgeStoredFiles(Collection)} for the
     * half that touches disk, which must not run until the delete has actually
     * succeeded.
     */
    @Transactional(readOnly = true)
    public List<UUID> findSubmissionIdsForUser(UUID userId) {
        return feedbackRepository.findIdsByUserId(userId);
    }

    /**
     * R21 — removes the attachment bytes of the given submissions.
     *
     * Deliberately takes ids rather than a user: by the time this is safe to
     * call, the account and its rows are already gone, so there is nothing left
     * to look them up from. Call it only AFTER the delete has committed to the
     * database — running it first destroys the files of an account that may yet
     * survive a failed delete, and there is no recovering them.
     *
     * Best-effort by construction — {@link FeedbackAttachmentStorageService#deleteAllForFeedback}
     * logs and swallows: a filesystem that refuses a delete must not be able to
     * undo an account removal that has already happened.
     */
    public void purgeStoredFiles(Collection<UUID> feedbackIds) {
        feedbackIds.forEach(storageService::deleteAllForFeedback);
    }

    private Feedback requireFeedback(UUID feedbackId, User requester, boolean adminMayAct) {
        return requireFeedback(feedbackId, requester, adminMayAct, false);
    }

    /**
     * @param lockForWrite takes a pessimistic write lock on the submission row.
     *                     Upload only: it is what makes the attachment count a
     *                     limit rather than a suggestion. Read paths must pass
     *                     false — serving an image is no reason to make
     *                     concurrent readers queue behind each other.
     */
    private Feedback requireFeedback(UUID feedbackId, User requester, boolean adminMayAct, boolean lockForWrite) {
        Feedback feedback = (lockForWrite
                ? feedbackRepository.findByIdForUpdate(feedbackId)
                : feedbackRepository.findById(feedbackId))
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
