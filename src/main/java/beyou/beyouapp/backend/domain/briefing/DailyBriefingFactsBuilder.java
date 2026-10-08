package beyou.beyouapp.backend.domain.briefing;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;
import beyou.beyouapp.backend.domain.briefing.dto.GoalPace;
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
import beyou.beyouapp.backend.domain.mood.dto.MoodEntryResponseDTO;
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

    /**
     * How many days of history the narrator's signals are read from: this week and the one
     * before it, so "better than last week" is a comparison and not a guess.
     *
     * <p>Widens the one snapshot query rather than adding a second. The retroactive window
     * is the most recent seven of these days and is filtered out of the same list.
     */
    static final int SIGNAL_DAYS = 14;

    /** How many slipping or steady items the prompt names. More than three is a list. */
    static final int MAX_PATTERNS = 3;

    /**
     * How many days an item has to have come up before "checked every time" says anything.
     * Two out of two is luck; four out of four is a habit holding.
     */
    static final int STEADY_MIN_DAYS = 4;

    /**
     * Percentage points a goal may trail its straight line before it counts as behind.
     *
     * <p>Five, so a goal one tap short on a Tuesday is not flagged. The line itself is a
     * simplification (see {@link GoalPace}), and a verdict sharper than the model it rests
     * on would be false precision.
     */
    static final int PACE_TOLERANCE_POINTS = 5;

    private final RoutineSnapshotRepository snapshotRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final GoalRepository goalRepository;
    private final FocusCycleRepository focusCycleRepository;
    private final MoodService moodService;
    private final UserStreakService userStreakService;
    private final XpDecayCalculator xpDecayCalculator;

    /**
     * Both halves of the facts, plus what only the narrator reads.
     *
     * @param signals the two-week patterns behind the prose. Never serialized; see
     *                {@link WeekSignals} for why
     */
    public record Facts(YesterdayRecap yesterday, TodayAhead today, WeekSignals signals) {

        /** The account's own day these facts describe. */
        public LocalDate date() {
            return yesterday.date().plusDays(1);
        }
    }

    /**
     * @param user  the account, already loaded
     * @param today the account's own day, resolved by the caller through
     *              {@code UserDateResolver} so that this stays testable against a fixed date
     */
    @Transactional(readOnly = true)
    public Facts build(User user, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate windowStart = yesterday.minusDays(BACKFILL_DAYS - 1L);
        LocalDate signalStart = yesterday.minusDays(SIGNAL_DAYS - 1L);

        // One query for two weeks, checks included. Asking day by day would be fourteen
        // round trips on the request that opens the dashboard. The retroactive window is
        // the recent half of the same list, and it is filtered rather than queried again so
        // that the recovery panel can never read a day the check path would refuse.
        List<RoutineSnapshot> fortnight = snapshotRepository
                .findAllByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                        user.getId(), signalStart, yesterday);
        List<RoutineSnapshot> window = fortnight.stream()
                .filter(s -> !s.getSnapshotDate().isBefore(windowStart))
                .toList();

        // Levels only, reduced on the line that reads them. The response DTO carries the
        // journal text too, and nothing past this point has any business holding it.
        Map<LocalDate, Integer> moods = new HashMap<>();
        for (MoodEntryResponseDTO entry : moodService.getRange(user, signalStart, yesterday)) {
            moods.put(entry.date(), entry.mood());
        }

        return new Facts(
                buildYesterday(user, yesterday, window, moods.get(yesterday)),
                buildToday(user, today, window, yesterday),
                buildSignals(fortnight, moods, yesterday));
    }

    // ---- yesterday ----

    private YesterdayRecap buildYesterday(User user, LocalDate yesterday,
                                          List<RoutineSnapshot> window, Integer moodLevel) {
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
                    moodLevel);
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
                moodLevel);
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
        List<Goal> openGoals = openGoals(user);

        return new TodayAhead(
                scheduledItems,
                scheduledToday,
                streak.currentStreak(),
                user.getMaxConstance() == null ? 0 : user.getMaxConstance(),
                goalsApproaching(openGoals, today),
                recoveryWindow(user, window, yesterday, today),
                goalsAhead(openGoals, today));
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
     * Every goal still in play: not completed, and not archived (put away means not on
     * today's mind). Read once and shared by both goal lists below.
     */
    private List<Goal> openGoals(User user) {
        return goalRepository.findAllByUserId(user.getId()).orElseGet(List::of).stream()
                .filter(goal -> goal.getStatus() != GoalStatus.COMPLETED)
                .filter(goal -> !Boolean.TRUE.equals(goal.getComplete()))
                .filter(goal -> goal.getArchivedAt() == null)
                .filter(goal -> goal.getEndDate() != null)
                .toList();
    }

    /**
     * Goals near their end date, soonest first, for the app builds already installed.
     *
     * <p>Goals whose end date is further off than {@link #GOAL_HORIZON_DAYS} are left out.
     * Overdue goals are kept: a goal whose date has passed with the target unmet is worth
     * saying, and hiding it because the number went negative would be the panel lying by
     * omission. Unchanged since it shipped; {@link #goalsAhead} is what current clients read.
     */
    private List<GoalAhead> goalsApproaching(List<Goal> openGoals, LocalDate today) {
        return openGoals.stream()
                .filter(goal -> ChronoUnit.DAYS.between(today, goal.getEndDate()) <= GOAL_HORIZON_DAYS)
                .sorted(Comparator.comparing(Goal::getEndDate))
                .limit(MAX_GOALS_AHEAD)
                .map(goal -> toGoalAhead(goal, today))
                .toList();
    }

    /**
     * The goals the user is heading toward, closest to their end date on either side.
     *
     * <p>No horizon, which is the point of this list: somebody whose goals all end in
     * March still has somewhere they are going, and the morning panel is where that gets
     * said. Sorted by distance from today in either direction rather than by end date, so a
     * goal two days overdue sits beside one due in two days. On a tie the upcoming goal goes
     * first, since that one can still be made.
     *
     * <p>A goal overdue by more than {@link #GOAL_HORIZON_DAYS} goes to the back whatever
     * its distance. By then it has been left, not missed, and ranking it by distance would
     * let something abandoned two months ago push out a goal the user is working toward in
     * three. It still fills a slot nothing else wants.
     */
    private List<GoalAhead> goalsAhead(List<Goal> openGoals, LocalDate today) {
        Comparator<Goal> staleLast = Comparator.comparing(
                goal -> ChronoUnit.DAYS.between(today, goal.getEndDate()) < -GOAL_HORIZON_DAYS);
        Comparator<Goal> byDistance = Comparator.comparingLong(
                goal -> Math.abs(ChronoUnit.DAYS.between(today, goal.getEndDate())));
        Comparator<Goal> upcomingFirst = Comparator.comparing(
                goal -> goal.getEndDate().isBefore(today));
        return openGoals.stream()
                .sorted(staleLast.thenComparing(byDistance).thenComparing(upcomingFirst)
                        .thenComparing(Goal::getEndDate))
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
        long daysRemaining = ChronoUnit.DAYS.between(today, goal.getEndDate());
        double remaining = Math.max(0d, target - current);
        boolean reached = target > 0 && remaining == 0d;
        int expected = expectedPercent(goal.getStartDate(), goal.getEndDate(), today);

        GoalPace pace;
        if (reached) {
            pace = GoalPace.REACHED;
        } else if (daysRemaining < 0) {
            pace = GoalPace.OVERDUE;
        } else if (percent + PACE_TOLERANCE_POINTS < expected) {
            pace = GoalPace.BEHIND;
        } else {
            pace = GoalPace.ON_TRACK;
        }

        // Today counts as one of the days left, so a goal due today asks for the whole
        // remainder today rather than dividing by zero.
        Double perDay = (daysRemaining < 0 || reached || target <= 0)
                ? null
                : roundOneDecimal(remaining / (daysRemaining + 1));

        return new GoalAhead(goal.getId(), goal.getName(), goal.getIconId(),
                current, target, goal.getUnit(), goal.getEndDate(),
                daysRemaining, percent, remaining, perDay, expected, pace);
    }

    /**
     * Where a straight line from start to end says the goal should be by today, 0 to 100.
     *
     * <p>Counts today as elapsed, the same way {@code requiredPerDay} counts it as still
     * available: the morning panel speaks for the whole of the day it opens on. A goal whose
     * start is after its end, which the API does not prevent, reads as fully elapsed rather
     * than throwing.
     */
    static int expectedPercent(LocalDate start, LocalDate end, LocalDate today) {
        if (start == null || end == null) {
            return 0;
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span <= 0) {
            return 100;
        }
        long elapsed = ChronoUnit.DAYS.between(start, today) + 1;
        long clamped = Math.max(0, Math.min(span, elapsed));
        return (int) Math.round(clamped * 100d / span);
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10d) / 10d;
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

    // ---- signals, for the narrator only ----

    /**
     * Two weeks of snapshots, read for what they say rather than for what is still open.
     *
     * <p>Skips are left out of every count here, on both sides of the fraction. A skip is
     * the user answering "not today", and counting it as asked-but-not-done would turn a
     * deliberate choice into a miss the narrator then comments on.
     *
     * <p>Items still open this week are counted as not checked, even though most of them can
     * still be checked from the dialog. That is the honest reading of the week as it stands
     * when the prose is written, and the prose is only ever about a pattern, never about
     * whether one particular item is still open.
     */
    private WeekSignals buildSignals(List<RoutineSnapshot> fortnight, Map<LocalDate, Integer> moods,
                                     LocalDate yesterday) {
        LocalDate weekStart = yesterday.minusDays(6);
        List<RoutineSnapshot> thisWeek = fortnight.stream()
                .filter(s -> !s.getSnapshotDate().isBefore(weekStart))
                .toList();
        List<RoutineSnapshot> lastWeek = fortnight.stream()
                .filter(s -> s.getSnapshotDate().isBefore(weekStart))
                .toList();

        // Keyed by the live item when the snapshot kept its id, by name otherwise, so a habit
        // that sits in two routines still reads as one habit. Snapshots arrive oldest first,
        // so the name kept is the most recent one, which is what the user calls it now.
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (RoutineSnapshot snapshot : thisWeek) {
            for (SnapshotCheck check : snapshot.getChecks()) {
                if (check.isSkipped()) {
                    continue;
                }
                String key = itemKey(check);
                int[] askedAndChecked = counts.computeIfAbsent(key, k -> new int[2]);
                askedAndChecked[0]++;
                if (check.isChecked()) {
                    askedAndChecked[1]++;
                }
                names.put(key, check.getItemName());
            }
        }
        List<WeekSignals.ItemPattern> patterns = counts.entrySet().stream()
                .map(e -> new WeekSignals.ItemPattern(
                        names.get(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .toList();

        // Slipping: open on at least two days, and on at least half the days it came up. Both
        // conditions, because one miss in two is noise and two misses in fourteen are fine.
        List<WeekSignals.ItemPattern> slipping = patterns.stream()
                .filter(p -> p.missed() >= 2 && p.missed() * 2 >= p.asked())
                .sorted(Comparator.comparingInt(WeekSignals.ItemPattern::missed).reversed()
                        .thenComparing(WeekSignals.ItemPattern::name))
                .limit(MAX_PATTERNS)
                .toList();
        List<WeekSignals.ItemPattern> steady = patterns.stream()
                .filter(p -> p.asked() >= STEADY_MIN_DAYS && p.missed() == 0)
                .sorted(Comparator.comparingInt(WeekSignals.ItemPattern::asked).reversed()
                        .thenComparing(WeekSignals.ItemPattern::name))
                .limit(MAX_PATTERNS)
                .toList();

        int moodDays = (int) moods.keySet().stream()
                .filter(day -> !day.isBefore(weekStart) && !day.isAfter(yesterday))
                .count();

        return new WeekSignals(
                completionPercent(thisWeek),
                completionPercent(lastWeek),
                slipping,
                steady,
                averageMood(moods, weekStart, yesterday),
                averageMood(moods, weekStart.minusDays(7), weekStart.minusDays(1)),
                moodDays);
    }

    private static String itemKey(SnapshotCheck check) {
        return check.getOriginalItemId() != null
                ? check.getItemType() + ":" + check.getOriginalItemId()
                : check.getItemType() + ":name:" + check.getItemName();
    }

    /** Checked over asked, skips out of both. Null when nothing was asked at all. */
    private static Integer completionPercent(List<RoutineSnapshot> snapshots) {
        int asked = 0;
        int checked = 0;
        for (RoutineSnapshot snapshot : snapshots) {
            for (SnapshotCheck check : snapshot.getChecks()) {
                if (check.isSkipped()) {
                    continue;
                }
                asked++;
                if (check.isChecked()) {
                    checked++;
                }
            }
        }
        return asked == 0 ? null : (int) Math.round(checked * 100d / asked);
    }

    private static Double averageMood(Map<LocalDate, Integer> moods, LocalDate from, LocalDate to) {
        List<Integer> levels = moods.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(from) && !e.getKey().isAfter(to))
                .map(Map.Entry::getValue)
                .toList();
        if (levels.isEmpty()) {
            return null;
        }
        double mean = levels.stream().mapToInt(Integer::intValue).average().orElse(0d);
        return roundOneDecimal(mean);
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
