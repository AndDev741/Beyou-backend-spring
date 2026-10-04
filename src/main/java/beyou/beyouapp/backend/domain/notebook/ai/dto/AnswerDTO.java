package beyou.beyouapp.backend.domain.notebook.ai.dto;

import java.util.List;

/** An answer in markdown with [n] markers, and the citations those markers point at. */
public record AnswerDTO(String markdown, List<CitationDTO> citations) {
}
