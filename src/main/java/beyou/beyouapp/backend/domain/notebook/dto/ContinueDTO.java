package beyou.beyouapp.backend.domain.notebook.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The "Continue" card: the page opened most recently, and what is being studied inside it.
 *
 * @param studyingTitle the leaf under the page that is being studied, when there is one
 */
public record ContinueDTO(
        UUID pageId,
        String title,
        String icon,
        UUID topicId,
        String topicTitle,
        String studyingTitle,
        ProgressDTO progress,
        Instant lastOpenedAt) {
}
