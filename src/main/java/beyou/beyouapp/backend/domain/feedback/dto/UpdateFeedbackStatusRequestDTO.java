package beyou.beyouapp.backend.domain.feedback.dto;

import beyou.beyouapp.backend.domain.feedback.FeedbackStatus;
import jakarta.validation.constraints.NotNull;

/**
 * R11/R15/KD4 — move a submission between triage states.
 *
 * Deliberately carries nothing but the target state: there is no optional
 * message here, because a message to the user is a reply, and a reply is its
 * own endpoint. Keeping the two apart is what makes it structurally impossible
 * for a status change to notify anybody.
 */
public record UpdateFeedbackStatusRequestDTO(
        @NotNull(message = "Status is required")
        FeedbackStatus status
) {
}
