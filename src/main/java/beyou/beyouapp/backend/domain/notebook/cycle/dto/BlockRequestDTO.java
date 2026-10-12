package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.GoalSource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One block of the list {@code PUT /notebook/cycles/{id}/blocks} replaces the queue with.
 *
 * @param id         an existing block of the cycle (its lap progress stays), or null for a new one
 * @param goalSource GOAL blocks: NEXT (the default when null) or PAGE. Ignored for TIME
 * @param pageId     GOAL + PAGE: the page to understand. TIME: an optional hint. Either way the
 *                   block's subject or a page below it in the home tree
 */
public record BlockRequestDTO(
        UUID id,
        @NotNull UUID topicId,
        @NotNull BlockMode mode,
        @NotNull @Min(5) @Max(600) Integer plannedMinutes,
        GoalSource goalSource,
        UUID pageId,
        Boolean requireQuiz,
        @Size(max = 200) String note) {
}
