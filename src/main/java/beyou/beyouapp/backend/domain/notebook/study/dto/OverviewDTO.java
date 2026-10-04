package beyou.beyouapp.backend.domain.notebook.study.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.ai.dto.CitationDTO;

/** "What your sources cover": a short summary and questions to start from. */
public record OverviewDTO(UUID id, String summary, List<String> questions, List<CitationDTO> citations,
        Instant createdAt) {
}
