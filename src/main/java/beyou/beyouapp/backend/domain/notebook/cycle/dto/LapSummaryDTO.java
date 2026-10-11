package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.time.Instant;

/** One lap in numbers. {@code closedAt} is null for the lap in progress. */
public record LapSummaryDTO(int number, Instant startedAt, Instant closedAt, int minutes, int plannedMinutes,
        int blocksClosed, int blocks, int understood, int turnsUsed) {
}
