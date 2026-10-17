package beyou.beyouapp.backend.domain.notebook.dto;

import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;
import jakarta.validation.constraints.Size;

/**
 * What to add at the end of a page: markdown as blocks, a diagram as one diagram block, or both
 * (markdown first). "Save to page" from the study room and the assistant. At least one of the
 * two must carry something, and a diagram must survive DiagramSanitizer; the service refuses
 * either with INVALID_REQUEST.
 */
public record AppendRequestDTO(@Size(max = 20_000) String markdown, DiagramSpec diagram) {
}
