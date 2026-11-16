package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionStatus;

/** One interview question. {@code answer} and {@code answeredAt} are null until it is answered. */
public record SecondBrainQuestionDTO(
    UUID id,
    SecondBrainQuestionKind kind,
    String text,
    SecondBrainQuestionStatus status,
    String answer,
    Instant createdAt,
    Instant answeredAt
) {}
