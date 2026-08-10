package beyou.beyouapp.backend.domain.checkday;

import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import beyou.beyouapp.backend.domain.common.CheckProgress;

/**
 * Derives an owner's {@link CheckProgress} from its stored {@link EntityCheckDay} rows.
 *
 * <p>KTD16: every scalar here is a pure function of the rows. The count is the done rows,
 * the dates are their extremes, the streak is the walk-back from a reference day, and the
 * record is the running maximum. Nothing increments anything. Three writers each nudging a
 * counter by one is the pattern that produced the unfloored {@code constance} this feature
 * replaces — a single derivation cannot drift, and repairing a wrong day repairs every
 * scalar that depends on it.
 *
 * <p>Static rather than a bean, matching {@code CheckXpCalculator},
 * {@code UserDateResolver} and {@code ScheduledOnDayResolver}: it holds no state, reads
 * nothing, and needs no injection point.
 */
public final class CheckProgressCalculator {

    private CheckProgressCalculator() {}

    /**
     * Recomputes every scalar for one owner.
     *
     * @param rows            that owner's stored days, in any order. Duplicates for the same
     *                        day are impossible in the database
     *                        ({@code uk_entity_check_day_owner_day}); if one shows up anyway
     *                        the later entry in the list wins rather than being counted twice.
     * @param referenceDay    the day the streak is counted back from — the day being written
     *                        on the live path, the edited day on the back-dated path.
     * @param storedBestStreak the record already on the entity. R13: the record never
     *                        decreases, so a recompute can only raise it.
     * @return a fresh {@code CheckProgress}. The caller decides whether to copy it onto the
     *         entity; nothing is mutated here.
     */
    public static CheckProgress recompute(List<EntityCheckDay> rows, LocalDate referenceDay,
                                          int storedBestStreak) {
        NavigableMap<LocalDate, CheckDayOutcome> byDay = index(rows);

        int totalCheckIns = 0;
        LocalDate firstCheckIn = null;
        LocalDate lastCheckIn = null;
        for (var entry : byDay.entrySet()) {
            if (entry.getValue() != CheckDayOutcome.DONE) {
                continue;
            }
            totalCheckIns++;
            if (firstCheckIn == null) {
                firstCheckIn = entry.getKey();
            }
            lastCheckIn = entry.getKey();
        }

        int currentStreak = currentStreak(byDay, referenceDay);

        CheckProgress progress = new CheckProgress();
        progress.setTotalCheckIns(totalCheckIns);
        progress.setFirstCheckInDate(firstCheckIn);
        progress.setLastCheckInDate(lastCheckIn);
        progress.setCurrentStreak(currentStreak);
        // R13 — the record is the running maximum, never the latest value.
        progress.setBestStreak(Math.max(Math.max(storedBestStreak, 0), currentStreak));
        return progress;
    }

    /**
     * The streak running back from {@code referenceDay}, in days actually checked off.
     *
     * <p>The walk classifies each day three ways rather than two:
     * <ul>
     *   <li>{@code DONE} counts and continues;</li>
     *   <li>{@code SKIPPED} (R12), {@code NOT_SCHEDULED}, {@code NOT_IN_ROUTINE} and
     *       <em>a day with no row at all</em> (R18, KTD19) continue without counting;</li>
     *   <li>{@code MISSED} ends the walk.</li>
     * </ul>
     *
     * <p>The missing-row case is the load-bearing one. A day nobody ever closed is unknown,
     * not failed, so a night of scheduler downtime does not read back as a broken streak and
     * a bad write window stays repairable by deleting the affected days.
     *
     * <p>That leniency needs a floor, or an owner whose every stored day is neutral would
     * walk backwards forever. The walk stops at the owner's earliest stored row: before that
     * day the owner has no history at all, so there is nothing left to continue through.
     */
    private static int currentStreak(NavigableMap<LocalDate, CheckDayOutcome> byDay,
                                     LocalDate referenceDay) {
        if (byDay.isEmpty() || referenceDay == null) {
            return 0;
        }
        LocalDate earliest = byDay.firstKey();
        int streak = 0;
        for (LocalDate day = referenceDay; !day.isBefore(earliest); day = day.minusDays(1)) {
            CheckDayOutcome outcome = byDay.get(day);
            if (outcome == CheckDayOutcome.MISSED) {
                break;
            }
            if (outcome == CheckDayOutcome.DONE) {
                streak++;
            }
        }
        return streak;
    }

    /** Rows keyed by day, sorted, so the extremes and the walk both read off one structure. */
    private static NavigableMap<LocalDate, CheckDayOutcome> index(List<EntityCheckDay> rows) {
        NavigableMap<LocalDate, CheckDayOutcome> byDay = new TreeMap<>();
        if (rows == null) {
            return byDay;
        }
        for (EntityCheckDay row : rows) {
            if (row == null || row.getDay() == null || row.getOutcome() == null) {
                continue;
            }
            byDay.put(row.getDay(), row.getOutcome());
        }
        return byDay;
    }
}
