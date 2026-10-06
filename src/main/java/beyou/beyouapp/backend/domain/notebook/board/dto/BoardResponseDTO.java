package beyou.beyouapp.backend.domain.notebook.board.dto;

import java.util.List;
import java.util.UUID;

public record BoardResponseDTO(UUID pageId, List<BoardNodeDTO> nodes, List<BoardEdgeDTO> edges) {
}
