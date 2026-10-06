package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.NotebookStatus;

/** A dot in a topic card's roadmap thumbnail. Position as stored on the board. */
public record MiniNodeDTO(UUID id, double x, double y, NotebookStatus status) {
}
