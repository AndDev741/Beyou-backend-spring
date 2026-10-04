package beyou.beyouapp.backend.domain.notebook.board.dto;

import java.util.UUID;

public record BoardEdgeDTO(UUID id, UUID source, UUID target) {
}
