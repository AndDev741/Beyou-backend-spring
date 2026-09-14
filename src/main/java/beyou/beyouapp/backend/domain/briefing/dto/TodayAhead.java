package beyou.beyouapp.backend.domain.briefing.dto;

import java.util.List;

/**
 * What the day that is starting actually holds.
 *
 * @param scheduledItemCount habits and tasks on today's routine, however many routines
 *                           cover the day
 * @param scheduledToday     whether any routine covers today at all. A Mon/Wed/Fri user on
 *                           a Tuesday has nothing at risk, and the clients must not imply
 *                           otherwise — the account streak counts scheduled days, so an
 *                           unscheduled day cannot break it
 * @param currentStreak      the run standing now, from {@code UserStreakService}
 * @param bestStreak         the account record, so the clients can say "one off your best"
 *                           without doing the comparison twice
 * @param goalsApproaching   goals near their end date, soonest first, capped
 * @param recovery           older days still open inside the backfill window, or null when
 *                           there are none
 */
public record TodayAhead(
    int scheduledItemCount,
    boolean scheduledToday,
    int currentStreak,
    int bestStreak,
    List<GoalAhead> goalsApproaching,
    RecoveryWindow recovery
) {}
