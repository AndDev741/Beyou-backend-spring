package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.util.List;

/**
 * What answering a question did.
 *
 * @param question the question, now ANSWERED
 * @param learned  the facts the answer added to the profile, possibly none
 */
public record SecondBrainAnswerResultDTO(SecondBrainQuestionDTO question, List<SecondBrainFactDTO> learned) {}
