package beyou.beyouapp.backend.domain.briefing;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;
import beyou.beyouapp.backend.domain.briefing.dto.OpenItem;
import beyou.beyouapp.backend.domain.briefing.dto.RecoveryWindow;
import beyou.beyouapp.backend.domain.briefing.dto.TodayAhead;
import beyou.beyouapp.backend.domain.briefing.dto.YesterdayRecap;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.common.CheckXpCalculator;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.focus.FocusCycleRepository;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.mood.MoodService;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayCalculator;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * Everything in a briefing that is a fact rather than a phrasing.
 *
 * <p>This class exists because of one decision: <b>the model never computes anything the
 * user can see</b>. Percentages, days remaining, streak standing, what a late check still
 * pays — all of it is derived here, from the same rows the rest of the app reads, and the
 * generated prose is handed the results rather than the raw account.
 *
 * <p>What that buys is not tidiness. A free-tier model asked to work out which goals are at
 * risk gets the arithmetic wrong, invents goals that are not there, and answers differently
 * each morning for identical data. Deriving it here means the numbers on screen are always
 * right, and it means a morning where the whole chain is in cooldown still produces a usable
 * dialog instead of an empty one.
 *
 * <p>Recomputed on every read and never cached. The user can change these numbers from
 * inside the dialog — checking a forgotten habit moves the counts underneath — so a cached
 * copy would need invalidating from every check path, which is a worse trade than a handful
 * of indexed queries.
 */
@Component
@RequiredArgsConstructor
public class DailyBriefingFactsBuilder {

    /**
     * How far back a retroactive check is still accepted, mirroring
     * {@code RoutineSnapshotScheduler.MAX_BACKFILL_DAYS}.
     *
     * <p>Duplicated as a constant rather than imported because the scheduler's copy is
     * private, and left deliberately equal: a dialog offering a check for a day the snapshot
     * job will refuse is a lie, the same one {@code EngagementNudgeService.backfillDays}
     * guards against on the mail side.
     */
    public static final int BACKFILL_DAYS = 7;

    /**
     * How many approaching goals the panel carries.
     *
     * <p>Three, because the page has a carousel bullet under it and a list that scrolls is a
     * list nobody finishes. Someone tracking twelve goals is served by the goals screen,
     * not by a morning dialog.
     */
    public static final int MAX_GOALS_AHEAD = 3;

    /**
     * How close to its end date a goal has to be before the briefing mentions it.
     *
     * <p>Fourteen days is roughly the horizon at which a goal stops being something you
     * will get to and starts being something you have to plan around. Shorter and a
     * long-term goal never appears until it is already lost; longer and every goal is
     * always "approaching", which means none of them is.
     */
    public static final int GOAL_HORIZON_DAYS = 14;

    private final RoutineSnapshotRepository snapshotRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final GoalRepository goalRepository;
    private final FocusCycleRepository focusCycleRepository;
    private final MoodService moodService;
    private final UserStreakService userStreakService;
    private final XpDecayCalculator xpDecayCalculator;

    /** Both halves of the facts, from one pass over the window. */
    public record Facts(YesterdayRecap yesterday, TodayAhead today) {}

    /**
     * @param user  the account, already loaded
     * @param today the account's own day, resolved by the caller through
     *              {@code UserDateResolver} so that this stays testable against a fixed date
     */
    @Transactional(readOnly = true)
    public Facts build(User user, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate windowStart = yesterday.minusDays(BACKFILL_DAYS - 1L);

        // One query for the whole retroactive window, checks included. Asking day by day
        // would be seven round trips on the request that opens the dashboard.
        List<RoutineSnapshot> window = snapshotRepository
                .findAllByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                        user.getId(), windowStart, yesterday);

        return new Facts(
                buildYesterday(user, yesterday, window),
                buildToday(user, today, window, yesterday));
    }

    // ---- yesterday ----

    private YesterdayRecap buildYesterday(User user, LocalDate yesterday,
                                          List<RoutineSnapshot> window) {
        List<RoutineSnapshot> snapshots = window.stream()
                .filter(s -> yesterday.equals(s.getSnapshotDate()))
                .toList();

        if (snapshots.isEmpty()) {
            // No snapshot means no routine covered the day, so nothing was asked of the
            // user. Deliberately not rendered as a failure anywhere downstream: the clients
            // read hadRoutine=false and say so, and worthShowing() treats it as silence.
            //
            // It is also, briefly, what a user sees between midnight and the hour-0 pass in
            // their timezone. That window is under a minute and forcing snapshot creation
            // from a read path is a far larger change than it deserves.
            return new YesterdayRecap(yesterday, false, false, 0, 0, 0d, List.of(), 0,
                    moodOn(user, yesterday));
        }

        int done = 0;
        int skipped = 0;
        double xp = 0d;
        List<OpenItem> open = new ArrayList<>();

        for (RoutineSnapshot snapshot : snapshots) {
            for (SnapshotCheck check : snapshot.getChecks()) {
                if (check.isChecked()) {
                    done++;
                    xp += check.getXpGenerated();
                } else if (check.isSkipped()) {
                    skipped++;
                } else {
                    open.add(toOpenItem(user, snapshot, check));
                }
            }
        }

        // A day is complete when every snapshot of it is. SnapshotCheckService already
        // decided that under the account's ConstanceConfiguration, so nothing here
        // re-derives it — re-deriving would mean a second opinion that can disagree.
        boolean complete = snapshots.stream().allMatch(RoutineSnapshot::isCompleted);

        return new YesterdayRecap(yesterday, true, complete, done, skipped, xp,
                List.copyOf(open),
                focusCycleRepository.findDay(user.getId(), yesterday).size(),
                moodOn(user, yesterday));
    }

    private Integer moodOn(User user, LocalDate day) {
        return moodService.getDay(user, day).map(entry -> entry.mood()).orElse(null);
    }

    // ---- today ----

    private TodayAhead buildToday(User user, LocalDate today, List<RoutineSnapshot> window,
                                  LocalDate yesterday) {
        List<DiaryRoutine> routines = diaryRoutineRepository.findAllByUserId(user.getId());

        int scheduledItems = 0;
        boolean scheduledToday = false;
        for (DiaryRoutine routine : routines) {
            if (!ScheduledOnDayResolver.coversDay(routine, today)) {
                continue;
            }
            scheduledToday = true;
            scheduledItems += countItems(routine);
        }

        UserStreakService.UserStreak streak = userStreakService.streakOf(user, today);

        return new TodayAhead(
                scheduledItems,
                scheduledToday,
                streak.currentStreak(),
                user.getMaxConstance() == null ? 0 : user.getMaxConstance(),
                goalsApproaching(user, today),
                recoveryWindow(user, window, yesterday, today));
    }

    /**
     * How many habits and tasks a routine holds, across every section.
     *
     * <p>Walked by hand rather than through {@code DiaryRoutine.listItems()}, which throws on
     * anything but a LIST routine — it exists to give a list its single section's items in
     * drag order, and a DAILY routine has several sections and no such order. Counting is a
     * different question from ordering and works for both shapes.
     */
    private static int countItems(DiaryRoutine routine) {
        List<RoutineSection> sections = routine.getRoutineSections();
        if (sections == null) {
            return 0;
        }
        int total = 0;
        for (RoutineSection section : sections) {
            if (section == null) {
                continue;
            }
            total += section.getHabitGroups() == null ? 0 : section.getHabitGroups().size();
            total += section.getTaskGroups() == null ? 0 : section.getTaskGroups().size();
        }
        return total;
    }

    /**
     * Goals near their end date, soonest first.
     *
     * <p>Completed goals are left out, and so are goals whose end date is further off than
     * {@link #GOAL_HORIZON_DAYS}. Overdue goals are kept: a goal whose date has passed with
     * the target unmet is the single most useful thing this panel can say, and hiding it
     * because the number went negative would be the panel lying by omission.
     */
    private List<GoalAhead> goalsApproaching(User user, LocalDate today) {
        List<Goal> goals = goalRepository.findAllByUserId(user.getId()).orElseGet(List::of);

        return goals.stream()
                .filter(goal -> goal.getStatus() != GoalStatus.COMPLETED)
                .filter(goal -> !Boolean.TRUE.equals(goal.getComplete()))
                .filter(goal -> goal.getEndDate() != null)
                .filter(goal -> ChronoUnit.DAYS.between(today, goal.getEndDate()) <= GOAL_HORIZON_DAYS)
                .sorted(Comparator.comparing(Goal::getEndDate))
                .limit(MAX_GOALS_AHEAD)
                .map(goal -> toGoalAhead(goal, today))
                .toList();
    }

    private GoalAhead toGoalAhead(Goal goal, LocalDate today) {
        double target = goal.getTargetValue() == null ? 0d : goal.getTargetValue();
        double current = goal.getCurrentValue() == null ? 0d : goal.getCurrentValue();
        // Guarded, and not because a zero target is expected: goalBox.tsx carried this exact
        // division as a bug until it was fixed, and the same expression on the server would
        // be the same bug with a 500 attached.
        int percent = target > 0
                ? (int) Math.min(100, Math.round(current / target * 100))
                : 0;

        return new GoalAhead(goal.getId(), goal.getName(), goal.getIconId(),
                current, target, goal.getUnit(), goal.getEndDate(),
                ChronoUnit.DAYS.between(today, goal.getEndDate()), percent);
    }

    /**
     * Days older than yesterday that still have something open and are still inside the
     * window.
     *
     * <p>Null when there is nothing there, so the clients render no affordance at all rather
     * than an empty accordion. Yesterday is excluded on purpose: it is the left panel's own
     * subject, and listing it twice would make the dialog look like it is nagging.
     */
    private RecoveryWindow recoveryWindow(User user, List<RoutineSnapshot> window,
                                          LocalDate yesterday, LocalDate today) {
        List<OpenItem> older = new ArrayList<>();
        for (RoutineSnapshot snapshot : window) {
            if (!snapshot.getSnapshotDate().isBefore(yesterday)) {
                continue;
            }
            for (SnapshotCheck check : snapshot.getChecks()) {
                if (!check.isChecked() && !check.isSkipped()) {
                    older.add(toOpenItem(user, snapshot, check));
                }
            }
        }
        if (older.isEmpty()) {
            return null;
        }

        older.sort(Comparator.comparing(OpenItem::date));
        LocalDate oldest = older.get(0).date();

        // The day that stops being recoverable after today is the window's lower bound,
        // the same arithmetic NudgeEligibility.expiringWindow uses. One means tonight.
        LocalDate expiringAfterToday = yesterday.minusDays(BACKFILL_DAYS - 1L);
        long daysUntilExpiry = Math.max(0, ChronoUnit.DAYS.between(expiringAfterToday, oldest) + 1);

        // What a check on the oldest open day actually pays under THIS account's strategy.
        // A panel quoting the undecayed value would be promising XP the check will not give.
        int remainingXpPercent = (int) Math.round(
                xpDecayCalculator.calculateDecayedXp(100d, user.getXpDecayStrategy(), oldest, today));

        return new RecoveryWindow(oldest, daysUntilExpiry, remainingXpPercent, List.copyOf(older));
    }

    // ---- shared ----

    private OpenItem toOpenItem(User user, RoutineSnapshot snapshot, SnapshotCheck check) {
        // Exactly what SnapshotCheckService.checkSnapshotItem will compute when this item is
        // actually checked: the same base, the same strategy, the same two dates, and a zero
        // streak bonus because a late check has already broken the run. If the two ever
        // disagree the user is told one number and paid another.
        double base = CheckXpCalculator.calculate(check.getDifficulty(), check.getImportance(), 0);
        // UserDateResolver.today(user), NOT the `today` this builder was asked about. They are
        // the same value for every real request — the endpoint resolves the date the same way
        // — but the decay's second argument has to be whatever the CHECK PATH will read when
        // the user taps, and that path has only one source for it. Passing the briefed date
        // instead would make the advertised number drift from the paid one the moment a caller
        // asked about any day but the current one.
        double xpNow = xpDecayCalculator.calculateDecayedXp(
                base, user.getXpDecayStrategy(), snapshot.getSnapshotDate(),
                UserDateResolver.today(user));

        return new OpenItem(
                snapshot.getId(),
                check.getId(),
                snapshot.getSnapshotDate(),
                snapshot.getRoutine() != null ? snapshot.getRoutine().getId() : null,
                snapshot.getRoutineName(),
                check.getItemType(),
                check.getItemName(),
                check.getItemIconId(),
                check.getSectionName(),
                xpNow);
    }
}
