package beyou.beyouapp.backend.domain.notebook.study.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;

/**
 * A graded quiz. {@code passed} at 70% or better; {@code xpEarned} is non-zero only the first
 * time this quiz is passed.
 */
public record QuizResultDTO(int score, int total, boolean passed, List<QuizAnswerDTO> answers,
        double xpEarned, RefreshUiDTO refreshUi) {
}
