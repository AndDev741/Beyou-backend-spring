package beyou.beyouapp.backend.domain.notebook.study.dto;

import java.util.List;

/** A quiz question as the person sees it before answering: no answer, no explanation. */
public record QuizQuestionDTO(int index, String question, List<String> options) {
}
