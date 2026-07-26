package beyou.beyouapp.backend.domain.feedback.dto;

import java.util.UUID;

/**
 * Who wrote a submission, as the admin console sees it (R12).
 *
 * Admin-only: this never appears in {@link FeedbackResponseDTO}, and the
 * routes that carry it all live under {@code /feedback/admin/**}. The email is
 * here because triage sometimes means recognising a user who has written
 * before — not so the console can mail them from the browser: the reply path
 * is the only channel back to the user (R14/KD3).
 */
public record FeedbackSubmitterDTO(
        UUID id,
        String name,
        String email
) {
}
