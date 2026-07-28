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
     * <p>Ordering matters, in three separate ways.
     *
     * <p><b>1. Authorization before work.</b> The submission is read and the
     * requester checked WITHOUT a lock first. Uploading is the submitter's act,
     * not a triage action — an admin has no business adding images to somebody
     * else's report — and refusing an unauthorized or unknown submission has to
     * cost less than serving it, or the 403 path becomes the cheapest way to
     * make the server decode 25 megapixels.
     *
     * <p><b>2. Decode before the lock.</b> {@code validateAndEncode} allocates
     * the full raster, downscales it and re-encodes to JPEG — for a 25 MP upload
     * (the header-check ceiling) that is a hundred megabytes of allocation and a
     * bilinear resample, all CPU-bound and all unbounded by anything the
     * database knows about. It touches no shared state, so it does not belong in
     * a critical section, and holding a row lock across it would serialise every
     * concurrent upload to the same submission behind the slowest decode.
     *
     * <p><b>3. Lock only the count-and-insert-and-write.</b> The cap is a count
     * followed by an insert, and read-then-insert with nothing in between is not
     * a cap at all: two uploads that both read four both write a fifth, and the
     * submission ends up over the limit with no error raised anywhere. So the
     * row is re-read under a pessimistic write lock — one extra primary-key
     * SELECT, the price of taking the decode out of the critical section — and
     * from there the count each request reads is the count it inserts against.
     *
     * <p>The disk write stays INSIDE the locked section deliberately. It has to
     * stay inside the transaction: {@link FeedbackAttachmentStorageService#write}
     * throwing is what rolls the row back, and the row is written first so it
     * carries the real dimensions, so there is no ordering that keeps that
     * guarantee and drops the lock. The cost is bounded in a way the decode is
     * not — by this point the image is a downscaled JPEG of at most
     * {@link FeedbackAttachmentStorageService#MAX_DIMENSION} on its longest edge,
     * so it is a few hundred kilobytes to a temp file plus an atomic rename,
     * regardless of what arrived on the wire.
     */
    @Transactional
    public FeedbackAttachmentDTO addAttachment(UUID feedbackId, User requester, MultipartFile file) {
        requireFeedback(feedbackId, requester, false);

        FeedbackAttachmentStorageService.EncodedAttachment encoded = storageService.validateAndEncode(file);

        // --- critical section starts here ---
        Feedback feedback = requireFeedback(feedbackId, requester, false, true);

        long existing = attachmentRepository.countByFeedbackId(feedbackId);
        if (existing >= MAX_ATTACHMENTS_PER_FEEDBACK) {
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_LIMIT_REACHED,
                    "A submission can carry at most " + MAX_ATTACHMENTS_PER_FEEDBACK + " attachments");
        }

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
     * to look them up from. Call it only AFTER the delete has COMMITTED —
     * running it any earlier, the flush included, destroys the files of an
     * account that may yet survive a rollback, and there is no recovering them.
     * {@code UserService.deleteUser} honours that by registering this on the
     * transaction's {@code afterCommit} callback rather than calling it inline.
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
     *                     Upload only, and only for the count-and-insert step:
     *                     it is what makes the attachment count a limit rather
     *                     than a suggestion, and holding it any wider than that
     *                     just makes concurrent uploaders queue for no benefit
     *                     (see {@link #addAttachment}). Read paths must pass
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
