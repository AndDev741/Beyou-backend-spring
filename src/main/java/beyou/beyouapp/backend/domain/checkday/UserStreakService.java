package beyou.beyouapp.backend.domain.checkday;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * R14 — the account-wide streak, counted in <em>scheduled</em> days rather than calendar
 * days.
 *
 * <p>KTD11 splits the two halves of the question across two sources, and neither can
 * answer alone:
 * <ul>
 *   <li><b>Was the day complete?</b> {@code User.completedDays}. That set is written by
 *       {@code UserService.markDayCompleted} under whichever {@code ConstanceConfiguration}
 *       the user chose — {@code ANY} once a single item is checked, {@code COMPLETE} once
 *       every item is checked or skipped. Nothing here re-derives it, so both modes keep
 *       working untouched.</li>
 *   <li><b>Was anything scheduled that day?</b> The {@code USER}-owned
 *       {@link EntityCheckDay} rows {@code DayCloseService} froze when the day ended.</li>
 * </ul>
 *
 * <p>KTD8 is why the second half reads stored rows instead of calling
 * {@code ScheduledOnDayResolver} for the day in question. The resolver answers against the
 * schedule as it stands <em>now</em>; asking it about last Tuesday lets a routine edited
 * yesterday rewrite a streak that was earned a month ago. The frozen row cannot be
 * rewritten by an edit, so the past stays put.
 *
 * <p>KTD16 names this class as the one documented exception to "every scalar is a pure
 * function of the stored rows". {@link CheckProgressCalculator} derives a habit's streak
 * entirely from that habit's rows. The account's rows do carry {@code DONE} —
 * {@code DayCloseService} stamps it on a day {@code completedDays} contains — but only from
 * the grace hour onward, so today and any day the pass has not reached yet has no row to
 * read. Completion therefore still comes from {@code completedDays}, which is current the
 * instant a check commits; the rows answer the other half of the question below.
 *
 * <p>Lives in {@code domain/checkday} rather than {@code user}: it reads the
 * {@code entity_check_day} table, sits beside the two other readers of it
 * ({@link CheckDayRecorder}, {@link CheckProgressCalculator}), and keeping it here spares
 * the {@code user} package a dependency on that repository — the package
 * {@code SecurityFilter} loads in full on every authenticated request.
 */
@Service
@RequiredArgsConstructor
public class UserStreakService {

    /**
     * R20 — a live streak with no scheduled and no completed day in this many consecutive
     * days reads as dormant. KTD25 keeps the threshold here rather than shipping the last
     * check-in date and letting every client invent its own cutoff.
     */
    public static final int DORMANT_AFTER_DAYS = 14;

    private final EntityCheckDayRepository entityCheckDayRepository;

    /**
     * The account streak and whether it has gone quiet.
     *
     * <p>Dormancy is reported <em>alongside</em> the number and never instead of it: a
     * user who kept a run of twelve and then stopped being scheduled anything still has a
     * run of twelve, and zeroing it would destroy the only thing worth flagging.
     */
    public record UserStreak(int currentStreak, boolean dormant) {

        /** No completed days at all — zero, and nothing to be dormant about. */
        public static final UserStreak NONE = new UserStreak(0, false);
    }

    /** The streak as of the user's own today (R15). */
    public UserStreak streakOf(User user) {
        return streakOf(user, user != null ? UserDateResolver.today(user) : null);
    }

    /**
     * The streak counted back from {@code referenceDay}.
     *
     * <p>Runs on the login path ({@code UserMapper}) and inside the check transaction
     * ({@code UserService.markDayCompleted}, {@code RefreshUiDtoBuilder}), so the read is
     * skipped entirely for an account with no completed days — a fresh user pays nothing.
     * Otherwise it is one indexed prefix scan of that user's own rows, the same shape and
     * cost as the recompute read {@link CheckDayRecorder} already does per check.
     *
     * @param referenceDay the day to count back from; {@code null} falls back to the
     *                     user's today rather than the server's.
     */
    public UserStreak streakOf(User user, LocalDate referenceDay) {
        if (user == null) {
            return UserStreak.NONE;
        }
        Set<LocalDate> completedDays = user.getCompletedDays();
        if (completedDays == null || completedDays.isEmpty()) {
            return UserStreak.NONE;
        }
        LocalDate reference = referenceDay != null ? referenceDay : UserDateResolver.today(user);
        List<EntityCheckDay> userRows = user.getId() == null
                ? List.of()
                : entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                        CheckDayOwnerType.USER, user.getId());
        return walk(completedDays, userRows, reference);
    }

    /**
     * The walk itself, as a pure function so every rule below is testable without a
     * database.
     *
     * <p>Each day between {@code referenceDay} and the earliest completed day is one of
     * three things:
     * <ul>
     *   <li><b>completed</b> — counts, and the walk continues;</li>
     *   <li><b>not completed, and its stored row says the day was scheduled</b> — the walk
     *       ends. This is the only thing that breaks a streak;</li>
     *   <li><b>anything else</b> — not completed and the row says {@code NOT_SCHEDULED} or
     *       {@code NOT_IN_ROUTINE}, or there is no row at all — neutral. The walk steps
     *       over it without counting.</li>
     * </ul>
     *
     * <p>The neutral case is what makes the streak schedule-aware: a Monday/Wednesday/Friday
     * user is not asked to act on a Tuesday, so a Tuesday cannot cost them anything. The
     * old rule broke on every gap day and, worse, returned zero outright whenever the
     * reference day was more than one day past the last completed day.
     *
     * <p>The missing-row case matters just as much: a night the day-close pass never ran
     * leaves no row, and an unknown day must read as neutral rather than as a failure.
     *
     * <p>That leniency needs a floor or a user whose every gap day is unscheduled would
     * walk backwards forever, on the login path and inside a check transaction both. The
     * walk stops at the earliest day in {@code completedDays}: nothing before it can add
     * to the count, so there is nothing left to continue through.
     */
    public static UserStreak walk(Set<LocalDate> completedDays, List<EntityCheckDay> userRows,
                                  LocalDate referenceDay) {
        if (completedDays == null || completedDays.isEmpty() || referenceDay == null) {
            return UserStreak.NONE;
        }

        Set<LocalDate> scheduledDays = scheduledDays(userRows);
        LocalDate earliestCompleted = Collections.min(completedDays);

        int streak = 0;
        for (LocalDate day = referenceDay; !day.isBefore(earliestCompleted); day = day.minusDays(1)) {
            if (completedDays.contains(day)) {
                streak++;
            } else if (scheduledDays.contains(day)) {
                break;
            }
        }

        boolean dormant = streak > 0
                && !activeRecently(scheduledDays, completedDays, referenceDay);
        return new UserStreak(streak, dormant);
    }

    /**
     * R20 — whether anything was scheduled <em>or</em> completed inside the trailing
     * {@value #DORMANT_AFTER_DAYS}-day window ending on {@code referenceDay}.
     *
     * <p>Both halves are needed, for the same reason the walk treats a missing row as
     * neutral: an absent row is unknown, not evidence that nothing was scheduled. A day
     * that has not closed yet has no row at all, so a user who completed this morning
     * would otherwise be declared dormant on the strength of rows that do not exist yet.
     * A completed day is activity whatever its row says.
     *
     * <p>Gated on a live streak by the caller. A user sitting at zero is not dormant, they
     * are simply at zero, and a brand-new account collects fourteen
     * {@code NOT_IN_ROUTINE} rows on its own — flagging that would be the only thing this
     * ever said about it.
     */
    private static boolean activeRecently(Set<LocalDate> scheduledDays,
                                          Set<LocalDate> completedDays, LocalDate referenceDay) {
        for (int daysBack = 0; daysBack < DORMANT_AFTER_DAYS; daysBack++) {
            LocalDate day = referenceDay.minusDays(daysBack);
            if (scheduledDays.contains(day) || completedDays.contains(day)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The days the account's own frozen rows say something was scheduled on.
     *
     * <p>Read as the complement of the two absence outcomes rather than as an equality
     * against {@code MISSED}. That is what keeps it right now that {@code DayCloseService}
     * also stamps {@code DONE} on the account: a completed day was a day something was
     * asked of the user, and an equality check would have started reading it as a day
     * nothing was scheduled.
     */
    private static Set<LocalDate> scheduledDays(List<EntityCheckDay> userRows) {
        Set<LocalDate> scheduled = new HashSet<>();
        if (userRows == null) {
            return scheduled;
        }
        for (EntityCheckDay row : userRows) {
            if (row == null || row.getDay() == null || row.getOutcome() == null) {
                continue;
            }
            if (row.getOutcome() == CheckDayOutcome.NOT_SCHEDULED
                    || row.getOutcome() == CheckDayOutcome.NOT_IN_ROUTINE) {
                continue;
            }
            scheduled.add(row.getDay());
        }
        return scheduled;
    }
}
