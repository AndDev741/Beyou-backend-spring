package beyou.beyouapp.backend.domain.feedback.dto;

import beyou.beyouapp.backend.domain.feedback.FeedbackContext;
import jakarta.validation.constraints.Size;

/**
 * R4 — context the client captures on the user's behalf. Every field is
 * optional: a submission must never fail because the client could not
 * determine, say, the active theme (R6).
 */
public record FeedbackContextDTO(
        @Size(max = FeedbackContext.SCREEN_MAX) String screen,
        @Size(max = FeedbackContext.APP_VERSION_MAX) String appVersion,
        @Size(max = FeedbackContext.PLATFORM_MAX) String platform,
        @Size(max = FeedbackContext.LANGUAGE_MAX) String language,
        @Size(max = FeedbackContext.THEME_MAX) String theme
) {
}
