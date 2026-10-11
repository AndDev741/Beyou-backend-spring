package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.time.Instant;

import beyou.beyouapp.backend.domain.notebook.cycle.LapBlockState;

/**
 * A block in the current lap.
 *
 * @param target        the concept of a GOAL block, null for TIME and for a GOAL block with
 *                      nothing left to understand (it then behaves like TIME)
 * @param targetPreview true when the concept is what the roadmap says now but is not fixed yet: a
 *                      NEXT block that is not the first open one of its topic only gets its
 *                      concept when its turn comes
 * @param quizPassed    null when the block does not ask for the quiz
 */
public record BlockProgressDTO(int lapNumber, int minutes, LapBlockState state, Instant closedAt,
        TargetDTO target, boolean targetPreview, Boolean quizPassed) {
}
