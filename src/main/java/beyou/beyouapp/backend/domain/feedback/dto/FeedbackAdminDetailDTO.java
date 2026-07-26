package beyou.beyouapp.backend.domain.feedback.dto;

import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A single submission opened in the admin console (R12).
 *
 * Everything triage needs in one read: the row's own fields, the context the
 * client captured (R4), the images the user attached (R3/R9) and the reply
 * thread already written (R14). Attachment bytes are fetched separately from
 * the {@code url} on each attachment.
 */
public record FeedbackAdminDetailDTO(
        UUID id,
        FeedbackCategory category,
        FeedbackStatus status,
        String body,
        FeedbackContextDTO context,
        FeedbackSubmitterDTO submitter,
        List<FeedbackAttachmentDTO> attachments,
        List<FeedbackReplyDTO> replies,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
