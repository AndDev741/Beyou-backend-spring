package beyou.beyouapp.backend.domain.notebook.study.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The option index picked for each question, in order. -1 (or a missing entry) is unanswered. */
public record QuizAnswersRequestDTO(@NotNull @Size(max = 30) List<Integer> answers) {
}
