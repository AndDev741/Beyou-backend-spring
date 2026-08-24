package beyou.beyouapp.backend.notification.engagement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;

/**
 * Which nudge an account has earned today, decided from values rather than from
 * repositories.
 *
 * <p>Pure and static on purpose. Everything here is a threshold judgement, thresholds set
 * without a baseline are guesses, and guesses get revised — so they have to be testable
 * without a database, a clock or a Spring context. {@code EngagementNudgeService} does the
 * loading; this decides.
 *
 * <p><b>Order matters and is deliberate.</b> Only one mail goes out per account per pass,
 * and when both triggers fire the expiring window wins: it is the one with a deadline that
 * passes tonight, while a streak can be defended tomorrow too.
 */
public final class NudgeEligibility {

    private NudgeEligibility() {
    }

    /**
     * @param today            the OWNER's local date, never the server's
     * @param backfillDays     how far back a retroactive check is still accepted
     *                         ({@code RoutineSnapshotScheduler.MAX_BACKFILL_DAYS})
     * @param frozenUserRows   the account-level {@link EntityCheckDay} rows already closed
     *                         for the window, in any order
     * @param remainingXpPercent what a check on the expiring day still earns, per this
     *                         account's decay strategy
     * @param scheduledToday   whether any routine covers today
     * @param completedToday   whether the account has already completed today
     * @param currentStreak    the run standing now
     * @param bestStreak       the account's record
     * @param minStreakToDefend the shortest run worth writing to somebody about
     * @param recordGap        how close to the record counts as "at risk"
     */
    public static Optional<NudgeDecision> decide(
            LocalDate today,
            int backfillDays,
            List<EntityCheckDay> frozenUserRows,
            int remainingXpPercent,
            boolean scheduledToday,
            boolean completedToday,
            int currentStreak,
            int bestStreak,
            int minStreakToDefend,
            int recordGap) {

        return expiringWindow(today, backfillDays, frozenUserRows, remainingXpPercent)
                .or(() -> streakRecord(scheduledToday, completedToday, currentStreak, bestStreak,
                        minStreakToDefend, recordGap));
    }

    /**
     * The oldest day still open for a retroactive check, when that day is about to fall
     * out of the window and was missed.
     *
     * <p>The window mirrors {@code RoutineSnapshotScheduler}: it backfills from
     * {@code yesterday.minusDays(MAX_BACKFILL_DAYS - 1)}, so the day that stops being
     * recoverable after today is exactly that lower bound. Anything older is already
     * gone and telling somebody about it would be cruel rather than useful.
     *
     * <p>Only {@code MISSED} counts. {@code NOT_SCHEDULED} is a day nothing was asked of
     * the account, {@code SKIPPED} is a deliberate "not today" the user already answered,
     * and {@code DONE} needs no rescue — mailing about any of those invents a failure, the
     * same mistake {@code DayCloseService} refuses to make when it declines to stamp
     * MISSED across a retroactive window.
     */
    private static Optional<NudgeDecision> expiringWindow(
            LocalDate today, int backfillDays, List<EntityCheckDay> frozenUserRows, int remainingXpPercent) {

        if (frozenUserRows == null || frozenUserRows.isEmpty() || backfillDays < 1) {
            return NudgeDecision.none();
        }

        LocalDate expiring = today.minusDays(1).minusDays(backfillDays - 1L);

        boolean recoverable = frozenUserRows.stream()
                .anyMatch(row -> row.getOwnerType() == CheckDayOwnerType.USER
                        && expiring.equals(row.getDay())
                        && row.getOutcome() == CheckDayOutcome.MISSED);

        return recoverable
                ? Optional.of(NudgeDecision.xpRecoveryWindow(expiring, remainingXpPercent))
                : NudgeDecision.none();
    }

    /**
     * A run at or near its own record, with something scheduled today and nothing done
     * yet.
     *
     * <p>Every condition here removes a population this mail would be wrong for.
     * {@code scheduledToday} keeps it away from a Mon/Wed/Fri user on a Tuesday, whose
     * streak is not at risk at all — the streak counts scheduled days, so an unscheduled
     * day cannot break it. {@code completedToday} keeps it away from somebody who already
     * did the work. {@code minStreakToDefend} keeps it away from a two-day run, where the
     * mail costs more goodwill than the streak is worth. And tying it to the record is
     * what makes it a warning about losing something built rather than a reprimand for a
     * day still in progress.
     */
    private static Optional<NudgeDecision> streakRecord(
            boolean scheduledToday, boolean completedToday, int currentStreak, int bestStreak,
            int minStreakToDefend, int recordGap) {

        if (!scheduledToday || completedToday) {
            return NudgeDecision.none();
        }
        if (currentStreak < minStreakToDefend) {
            return NudgeDecision.none();
        }
        // At the record, or within the configured gap below it. A run already past the
        // old record still qualifies: what is at risk then is the new record it is
        // setting, which is worth defending too.
        if (currentStreak < bestStreak - recordGap) {
            return NudgeDecision.none();
        }
        return Optional.of(NudgeDecision.streakRecordAtRisk(currentStreak, bestStreak));
    }

    /**
     * Reads a day's account-level outcome out of frozen rows, for callers that need it
     * without repeating the owner-type filter.
     */
    public static Optional<CheckDayOutcome> accountOutcomeOn(List<EntityCheckDay> rows, LocalDate day) {
        if (rows == null) {
            return Optional.empty();
        }
        return rows.stream()
                .filter(row -> row.getOwnerType() == CheckDayOwnerType.USER && day.equals(row.getDay()))
                .map(EntityCheckDay::getOutcome)
                .findFirst();
    }

    /** Convenience for callers holding a set of completed days rather than an entity. */
    public static boolean completedOn(Set<LocalDate> completedDays, LocalDate day) {
        return completedDays != null && completedDays.contains(day);
    }
}
