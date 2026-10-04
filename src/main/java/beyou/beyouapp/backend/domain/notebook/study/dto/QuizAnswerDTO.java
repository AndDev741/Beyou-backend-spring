package beyou.beyouapp.backend.domain.notebook.study.dto;

import beyou.beyouapp.backend.domain.notebook.ai.dto.CitationDTO;

/** One graded question, with what the person picked and why the right answer is right. */
public record QuizAnswerDTO(int index, int chosen, int correct, boolean right, String explanation,
        CitationDTO citation) {
}
