package beyou.beyouapp.backend.domain.feedback.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A stored attachment as the client sees it.
 *
 * {@code url} is the API-relative path the bytes are served from, so neither
 * web nor mobile has to know how attachments are addressed. {@code contentType}
 * is always {@code image/jpeg} — everything is re-encoded on the way in.
 */
public record FeedbackAttachmentDTO(
        UUID id,
        UUID feedbackId,
        String url,
        String contentType,
        int width,
        int height,
        long sizeBytes,
        LocalDateTime createdAt
) {
}
