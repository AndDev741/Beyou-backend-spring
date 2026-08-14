package beyou.beyouapp.backend.domain.habit.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import beyou.beyouapp.backend.domain.category.Category;

/**
 * One habit as the list and detail endpoints return it.
 *
 * <p>R2 — the check scalars ride this response so a client can draw a streak
 * without touching {@code routine_snapshot} or {@code snapshot_check}, and
 * without a second call. R9 — the per-day history deliberately does NOT ride
 * it: {@code HabitService.getHabits} is {@code @Cacheable} for thirty minutes
 * and a history inlined here would grow by one entry per habit per day, forever.
 * {@code GET /check-history} serves it instead.
 *
 * <p>R3 — {@code totalCheckIns} replaces the old {@code constance} field
 * outright rather than sitting beside it. The old name said "constancy" and
 * held a lifetime count; keeping both alive is how a wire format ends up with
 * two counters that drift apart.
 */
public record HabitResponseDTO(
    UUID id ,
    String name,
    String description,
    String motivationalPhrase,
    String iconId,
    int importance,
    int dificulty,
    List<Category> categories,
    double xp,
    double actualLevelXp,
    double nextLevelXp,
    int level,
    /** Days in the run up to and including the last check-in. */
    int currentStreak,
    /** R13 — the longest run ever reached. Never decreases. */
    int bestStreak,
    /** Lifetime count of days closed as done. Was {@code constance}. */
    int totalCheckIns,
    /** Null until the first check-in ever. */
    LocalDate firstCheckInDate,
    /**
     * R20/KTD25 — true when the run still stands but nothing has been checked
     * off in {@code UserStreakService.DORMANT_AFTER_DAYS} days. The number ships
     * unchanged beside it: a paused run is not a broken one.
     *
     * <p>The last check-in date is deliberately absent from this record. Shipping
     * it would let every client invent its own cutoff for "has this gone quiet",
     * which is exactly the judgement KTD25 keeps on the server.
     */
    boolean streakDormant,
    LocalDate createdAt,
    LocalDate updatedAt,
    Map<UUID, String> routines
) {

}
