package beyou.beyouapp.backend.domain.task.dto;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import beyou.beyouapp.backend.domain.category.dto.CategoryMiniDTO;

/**
 * One task as the list and detail endpoints return it.
 *
 * <p>R2 — the check scalars ride this response so a client draws a streak
 * without querying the snapshot tables. R9 — the per-day history does not ride
 * it; {@code GET /check-history} serves that.
 *
 * <p>R4 — a one-time task is checked once and never builds a run, so nothing
 * ever writes to its scalars and they read as zero here for the life of the
 * task. That is reported rather than hidden: {@code oneTimeTask} already tells
 * a client which kind it is looking at.
 */
public record TaskResponseDTO(
        UUID id,
        String name,
        String description,
        String iconId,
        Integer importance,
        Integer difficulty,
        Map<UUID,CategoryMiniDTO> categories,
        boolean oneTimeTask,
        /** Days in the run up to and including the last check-in. */
        int currentStreak,
        /** R13 — the longest run ever reached. Never decreases. */
        int bestStreak,
        /** Lifetime count of days closed as done. */
        int totalCheckIns,
        /** Null until the first check-in ever. */
        LocalDate firstCheckInDate,
        /**
         * R20/KTD25 — true when the run still stands but nothing has been checked
         * off in {@code UserStreakService.DORMANT_AFTER_DAYS} days. As on the habit
         * response, the last check-in date deliberately does not ship.
         */
        boolean streakDormant,
        LocalDate markedToDelete,
        LocalDate createdAt,
        LocalDate updatedAt
) {
}
