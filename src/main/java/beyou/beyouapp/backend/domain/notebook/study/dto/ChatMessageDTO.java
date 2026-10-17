package beyou.beyouapp.backend.domain.notebook.study.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.ai.dto.CitationDTO;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;

/**
 * One chat turn. {@code role} is USER or ASSISTANT; only answers carry citations, and only an
 * answer the tutor drew a picture for carries a {@code diagram} (cleaned, null otherwise).
 */
public record ChatMessageDTO(UUID id, String role, String content, List<CitationDTO> citations, Instant createdAt,
        DiagramSpec diagram) {
}
