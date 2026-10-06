package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

/** A line in a topic card's roadmap thumbnail, between two {@link MiniNodeDTO} ids. */
public record MiniEdgeDTO(UUID source, UUID target) {
}
