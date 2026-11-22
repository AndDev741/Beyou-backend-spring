package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.util.List;

/**
 * What answering a question did.
 *
 * @param question the question, now ANSWERED
 * @param learned  the facts the answer added to the profile, possibly none
 * @param notes    the notes the answer left on the person's things, possibly none. The page
 *                 reads the overview again when there are some, since the grouping is the
 *                 server's
 */
public record SecondBrainAnswerResultDTO(SecondBrainQuestionDTO question, List<SecondBrainFactDTO> learned,
        List<SecondBrainNoteDTO> notes) {

    /** With no notes. Server-built only, so the overload is safe (see SecondBrainOverviewDTO). */
    public SecondBrainAnswerResultDTO(SecondBrainQuestionDTO question, List<SecondBrainFactDTO> learned) {
        this(question, learned, List.of());
    }
}
