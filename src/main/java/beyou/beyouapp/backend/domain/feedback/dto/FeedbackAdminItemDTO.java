package beyou.beyouapp.backend.domain.feedback.dto;

import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the admin inbox (R11, R12).
 *
 * This is where {@code status} finally surfaces: the user-facing
 * {@link FeedbackResponseDTO} deliberately omits it, because triage state is
 * an internal tool and only a written reply speaks to the user (KD4).
 *
 * A row deliberately carries no attachment or reply counts — those would cost
 * a query per row, and the detail view is one click away.
 */
public record FeedbackAdminItemDTO(
        UUID id,
        FeedbackCategory category,
        FeedbackStatus status,
        String body,
        FeedbackContextDTO context,
        FeedbackSubmitterDTO submitter,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
