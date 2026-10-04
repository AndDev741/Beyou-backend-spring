package beyou.beyouapp.backend.domain.notebook.study.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.ai.dto.CitationDTO;

/** One chat turn. {@code role} is USER or ASSISTANT; only answers carry citations. */
public record ChatMessageDTO(UUID id, String role, String content, List<CitationDTO> citations, Instant createdAt) {
}
