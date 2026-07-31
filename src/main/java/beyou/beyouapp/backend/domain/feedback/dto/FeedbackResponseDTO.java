package beyou.beyouapp.backend.domain.feedback.dto;

import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What the submitting user gets back: a receipt of what was stored.
 *
 * Deliberately carries NO status (R11/KD4) — triage state is an internal tool
 * for the admin, and only a written reply speaks to the user. The admin view
 * gets its own DTO.
 */
public record FeedbackResponseDTO(
        UUID id,
        FeedbackCategory category,
        String body,
        FeedbackContextDTO context,
        LocalDateTime createdAt
) {
}
