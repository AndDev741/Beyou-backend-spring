package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.NotebookStatus;

/** One page whose status moved, so the client can repaint every board that shows it. */
public record PageStatusDTO(UUID pageId, NotebookStatus status) {
}
