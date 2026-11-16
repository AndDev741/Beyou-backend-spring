package beyou.beyouapp.backend.domain.secondbrain.dto;

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SecondBrainAnswerRequestDTO(
    @NotBlank
    @Size(max = SecondBrainQuestion.ANSWER_MAX)
    String answer
) {}
