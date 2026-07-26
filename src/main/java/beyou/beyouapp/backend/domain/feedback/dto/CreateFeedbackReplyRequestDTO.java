package beyou.beyouapp.backend.domain.feedback.dto;

import beyou.beyouapp.backend.domain.feedback.FeedbackReplyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * R14 — what the admin writes back. A reply is always a written message:
 * there is no empty reply, because an empty reply would be a status change
 * wearing a notification (R15/KD4).
 */
public record CreateFeedbackReplyRequestDTO(
        @NotBlank(message = "Reply body is required")
        @Size(max = FeedbackReplyService.REPLY_BODY_MAX, message = "Reply body is too long")
        String body
) {
}
