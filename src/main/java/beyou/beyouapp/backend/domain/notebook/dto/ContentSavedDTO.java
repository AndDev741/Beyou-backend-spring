package beyou.beyouapp.backend.domain.notebook.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The autosave's answer. Small on purpose: the editor already holds the document.
 *
 * @param contentRevision the revision the document is at now; the editor's next save starts from it
 */
public record ContentSavedDTO(UUID id, Instant updatedAt, long contentRevision) {
}
