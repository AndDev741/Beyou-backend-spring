package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.GoalSource;
import beyou.beyouapp.backend.domain.notebook.cycle.LapBlockState;
import beyou.beyouapp.backend.domain.notebook.dto.PageRefDTO;

/**
 * One block on today's card.
 *
 * <p>The same block can appear twice in one day: closed earlier today ({@code doneToday}) and
 * again further down when the slice wraps into the next lap ({@code nextLap}). A client keys rows
 * on blockId together with those two flags.
 *
 * @param credited     minutes the block holds in its lap
 * @param minutesToday its share of what is left of today; 0 for a block done today
 * @param start        "HH:mm" when the day has a start time, else null. Null for blocks done today
 * @param cardsDue     flashcards due on the pages of the block's subject (its home subtree)
 */
public record TodayEntryDTO(UUID blockId, int position, SubjectRefDTO topic, BlockMode mode, GoalSource goalSource,
        PageRefDTO page, String note, boolean requireQuiz, int plannedMinutes, int credited, int minutesToday,
        LapBlockState state, boolean doneToday, boolean nextLap, String start, String end, TargetDTO target,
        boolean targetPreview, Boolean quizPassed, int cardsDue) {
}
