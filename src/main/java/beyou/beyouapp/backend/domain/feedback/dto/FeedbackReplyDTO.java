package beyou.beyouapp.backend.domain.feedback.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A stored reply, as the admin console sees it.
 *
 * Never served to the submitting user: email is the return channel (KD3), so
 * the reply reaches them in their inbox and there is no route that reads this
 * back to them.
 */
public record FeedbackReplyDTO(
        UUID id,
        UUID feedbackId,
        String body,
        String authorName,
        LocalDateTime createdAt
) {
}
