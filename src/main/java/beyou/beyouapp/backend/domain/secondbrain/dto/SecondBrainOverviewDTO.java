package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;

/**
 * The whole Second Brain page in one read.
 *
 * @param insight     the generated overview, with nulls inside when none was written yet
 * @param goalsAhead  the goals the person is heading toward, the same records the morning
 *                    briefing carries. Empty when the GOALS source is off
 * @param routine     how the routine went this week against last. Null when ROUTINES is off
 * @param areas       the same two weeks by category, best first. Empty when ROUTINES is off
 * @param questions   the OPEN questions, oldest first
 * @param facts       the profile, oldest first
 * @param sources     all six switches in enum order, with the values in effect
 * @param aiAvailable whether an LLM endpoint is configured. False means the insight cannot be
 *                    refreshed and no follow-up question will be written
 * @param memory      the notes about the person's things, grouped by category; empty with none
 * @param oldNote     the hidden note the assistant kept before step 2, one sentence per line,
 *                    for the person to keep or discard; empty once it is gone
 */
public record SecondBrainOverviewDTO(
    SecondBrainInsightDTO insight,
    List<GoalAhead> goalsAhead,
    SecondBrainRoutineDTO routine,
    List<SecondBrainAreaDTO> areas,
    List<SecondBrainQuestionDTO> questions,
    List<SecondBrainFactDTO> facts,
    List<SecondBrainSourceDTO> sources,
    boolean aiAvailable,
    List<SecondBrainMemoryGroupDTO> memory,
    List<String> oldNote
) {

    /**
     * Without memory and without an old note. Only the server builds this DTO, so an overload
     * is safe here (no request body binds to it) and keeps every older construction site as it
     * was.
     */
    public SecondBrainOverviewDTO(SecondBrainInsightDTO insight, List<GoalAhead> goalsAhead,
            SecondBrainRoutineDTO routine, List<SecondBrainAreaDTO> areas,
            List<SecondBrainQuestionDTO> questions, List<SecondBrainFactDTO> facts,
            List<SecondBrainSourceDTO> sources, boolean aiAvailable) {
        this(insight, goalsAhead, routine, areas, questions, facts, sources, aiAvailable, List.of(), List.of());
    }
}
