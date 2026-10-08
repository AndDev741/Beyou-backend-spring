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
 * @param goalsApproaching   goals whose end date is within the two-week horizon, soonest
 *                           first, capped. Kept exactly as it shipped for the app builds
 *                           already installed, which render this list and nothing newer;
 *                           current clients read {@code goalsAhead} instead
 * @param recovery           older days still open inside the backfill window, or null when
 *                           there are none
 * @param goalsAhead         the active goals closest to their end date on either side,
 *                           capped, with their pace. Unlike {@code goalsApproaching} there
 *                           is no horizon: a goal three months out is still where the
 *                           user is heading, and the panel is the one place each morning
 *                           that says so
 */
public record TodayAhead(
    int scheduledItemCount,
    boolean scheduledToday,
    int currentStreak,
    int bestStreak,
    List<GoalAhead> goalsApproaching,
    RecoveryWindow recovery,
    List<GoalAhead> goalsAhead
) {}
