package beyou.beyouapp.backend.domain.secondbrain;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;

@Component
public class SecondBrainMapper {

    public SecondBrainFactDTO toDTO(SecondBrainFact fact) {
        return new SecondBrainFactDTO(
                fact.getId(),
                fact.getKind(),
                fact.getText(),
                fact.getOrigin(),
                fact.getCreatedAt(),
                fact.getUpdatedAt());
    }

    public SecondBrainQuestionDTO toDTO(SecondBrainQuestion question) {
        return new SecondBrainQuestionDTO(
                question.getId(),
                question.getKind(),
                question.getText(),
                question.getStatus(),
                question.getAnswer(),
                question.getCreatedAt(),
                question.getAnsweredAt());
    }
}
