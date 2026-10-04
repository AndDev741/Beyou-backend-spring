package beyou.beyouapp.backend.domain.notebook.ai.dto;

import java.util.List;

public record RoadmapDraftDTO(List<DraftNodeDTO> nodes, int totalHours) {
}
