package beyou.beyouapp.backend.domain.feedback.dto;

import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * R6 — everything the user has to provide: a category and a body. The context
 * is filled by the client, not the user, and is optional.
 */
public record CreateFeedbackRequestDTO(
        @NotNull(message = "Feedback category is required")
        FeedbackCategory category,

        @NotBlank(message = "Feedback body is required")
        @Size(max = FeedbackService.BODY_MAX, message = "Feedback body is too long")
        String body,

        @Valid
        FeedbackContextDTO context
) {
}
