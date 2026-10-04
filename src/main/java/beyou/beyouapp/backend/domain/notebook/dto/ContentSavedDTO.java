package beyou.beyouapp.backend.domain.notebook.dto;

import java.time.Instant;
import java.util.UUID;

/** The autosave's answer. Small on purpose: the editor already holds the document. */
public record ContentSavedDTO(UUID id, Instant updatedAt) {
}
