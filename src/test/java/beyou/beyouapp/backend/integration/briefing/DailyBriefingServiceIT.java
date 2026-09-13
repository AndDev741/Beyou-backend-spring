package beyou.beyouapp.backend.integration.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingRepository;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingService;
import beyou.beyouapp.backend.domain.briefing.NarrativeStatus;
import beyou.beyouapp.backend.domain.briefing.dto.DailyBriefingResponseDTO;
import beyou.beyouapp.backend.domain.briefing.dto.OpenItem;
import beyou.beyouapp.backend.domain.common.CheckXpCalculator;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleRepository;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckService;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;

/**
 * The Daily Briefing against a real Postgres.
 *
 * <p>Real rather than mocked for two reasons. The unique constraint that makes a briefing
 * one row per day is a database rule, and mocking the repository would prove nothing about
 * it. And the assertion that matters most here — that the XP the dialog advertises is the XP
 * the check actually pays — is only worth anything if the second half runs the real
 * {@code SnapshotCheckService} against real rows.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> {@code DailyBriefingWrites.findOrCreate}
 * runs REQUIRES_NEW so it can absorb a unique-constraint violation without poisoning anything
 * around it. Wrapping these tests in a transaction would suspend that and hand the write a
 * connection that cannot see the seeded rows, which would make
 * {@code askingTwiceLeavesOneRow} pass for entirely the wrong reason. Every account here gets
 * a random email instead, so the rows left behind in the shared container cannot reach
 * another test.
 *
 * <p>Narration is off for the whole class. Every rule under test is in the facts half, and
 * standing up a model would make these assertions depend on an upstream free tier being
 * awake. The same switch is what the e2e profile uses.
 */
@TestPropertySource(properties = "briefing.narration-enabled=false")
class DailyBriefingServiceIT extends AbstractIntegrationTest {

    @Autowired private DailyBriefingService briefingService;
    @Autowired private DailyBriefingRepository briefingRepository;
    @Autowired private SnapshotCheckService snapshotCheckService;
    @Autowired private RoutineSnapshotRepository snapshotRepository;
    @Autowired private SnapshotCheckRepository snapshotCheckRepository;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private UserRepository userRepository;

    /**
     * A Wednesday, so the seeded Mon/Wed/Fri schedule covers both it and the day before is a
     * Tuesday that it does not. Picked rather than derived from the wall clock: a date that
     * moves makes "was today scheduled" a different question every time the suite runs.
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private User user;
    private DiaryRoutine routine;

    @BeforeEach
    void seed() {
        user = newUser();
        routine = newRoutine(user, Set.of(WeekDay.Monday, WeekDay.Wednesday, WeekDay.Friday));
        // Re-read after the routine exists. SnapshotCheckService saves the authenticated
        // principal at the end of a check, and User cascades ALL to its routines — handing it
        // the instance from before the routine was created merges a stale collection over a
        // live row. In production the principal is loaded fresh per request by SecurityFilter,
        // so this is the test standing in for that, not a workaround for a bug.
        user = userRepository.findById(user.getId()).orElseThrow();
        authenticateAs(user);
    }

    // ---- what counts as open ----

    /**
     * The distinction the whole left panel rests on. A skip is something the user already
     * answered and a check is done; only the untouched rows are still waiting, and an
     * implementation that lumped skipped in with open would nag people for saying no.
     */
    @Test
    void openItems_areTheOnesNeitherCheckedNorSkipped() {
        RoutineSnapshot snapshot = snapshotFor(YESTERDAY, false);
        addCheck(snapshot, "Did it", check -> check.setChecked(true));
        addCheck(snapshot, "Said no", check -> check.setSkipped(true));
        addCheck(snapshot, "Left hanging", check -> {});
        persist(snapshot);

        DailyBriefingResponseDTO briefing = briefingService.briefingFor(user, TODAY);

        assertThat(briefing.yesterday().openItems())
                .extracting(OpenItem::itemName)
                .containsExactly("Left hanging");
        assertThat(briefing.yesterday().doneCount()).isEqualTo(1);
        assertThat(briefing.yesterday().skippedCount()).isEqualTo(1);
    }

    /**
     * The single most valuable assertion in this class.
     *
     * <p>The dialog prints what a late check is worth, then the user taps it and
     * {@code SnapshotCheckService} decides what it actually pays. Those are two separate
     * calculations in two separate classes, and a user told one number and given another
     * reads it as the app cheating. This runs both and compares.
     */
    @Test
    void advertisedXp_isExactlyWhatTheCheckPays() {
        RoutineSnapshot snapshot = snapshotFor(YESTERDAY, false);
        SnapshotCheck open = addCheck(snapshot, "Stretch", check -> {
            check.setDifficulty(4);
            check.setImportance(5);
        });
        persist(snapshot);

        DailyBriefingResponseDTO briefing = briefingService.briefingFor(user, TODAY);
        OpenItem advertised = briefing.yesterday().openItems().get(0);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshot.getId(), open.getId());

        double actuallyPaid = snapshotCheckRepository.findById(open.getId()).orElseThrow().getXpGenerated();
        assertThat(advertised.xpIfCheckedNow()).isCloseTo(actuallyPaid, within(0.0001));
        // And it is decayed rather than the full value. The line above would still pass if
        // both sides had forgotten the decay together, so the fact that one was applied at
        // all is pinned separately.
        assertThat(actuallyPaid).isLessThan(CheckXpCalculator.calculate(4, 5, 0));
    }

    // ---- the recovery window ----

    /**
     * Yesterday belongs to the left panel and older days belong to the collapsed hint.
     * Listing yesterday in both would make the dialog look like it is repeating itself.
     */
    @Test
    void recoveryWindow_holdsOlderDaysAndNeverYesterday() {
        RoutineSnapshot yesterdaySnapshot = snapshotFor(YESTERDAY, false);
        addCheck(yesterdaySnapshot, "Yesterday's", check -> {});
        persist(yesterdaySnapshot);

        RoutineSnapshot older = snapshotFor(TODAY.minusDays(4), false);
        addCheck(older, "Older", check -> {});
        persist(older);

        DailyBriefingResponseDTO briefing = briefingService.briefingFor(user, TODAY);

        assertThat(briefing.yesterday().openItems()).extracting(OpenItem::itemName)
                .containsExactly("Yesterday's");
        assertThat(briefing.today().recovery()).isNotNull();
        assertThat(briefing.today().recovery().openItems()).extracting(OpenItem::itemName)
                .containsExactly("Older");
        assertThat(briefing.today().recovery().oldestOpenDay()).isEqualTo(TODAY.minusDays(4));
    }

    /** Nothing older open means no affordance at all, not an empty one. */
    @Test
    void recoveryWindow_isAbsentWhenOnlyYesterdayIsOpen() {
        RoutineSnapshot snapshot = snapshotFor(YESTERDAY, false);
        addCheck(snapshot, "Only this", check -> {});
        persist(snapshot);

        assertThat(briefingService.briefingFor(user, TODAY).today().recovery()).isNull();
    }

    /**
     * A day outside the seven-day backfill window is gone: the snapshot job will not accept
     * a check for it, so offering one would be a lie. The row still exists — the test seeds
     * it eight days back deliberately — and the window has to be what excludes it.
     */
    @Test
    void recoveryWindow_ignoresDaysPastTheBackfillLimit() {
        RoutineSnapshot expired = snapshotFor(TODAY.minusDays(9), false);
        addCheck(expired, "Long gone", check -> {});
        persist(expired);

        RoutineSnapshot yesterdaySnapshot = snapshotFor(YESTERDAY, false);
        addCheck(yesterdaySnapshot, "Reachable", check -> {});
        persist(yesterdaySnapshot);

        DailyBriefingResponseDTO briefing = briefingService.briefingFor(user, TODAY);

        assertThat(briefing.today().recovery()).isNull();
    }

    // ---- goals ----

    @Test
    void goalsApproaching_keepsOverdueAndDropsCompleted() {
        newGoal("Overdue", TODAY.minusDays(3), 4d, 10d, GoalStatus.IN_PROGRESS, false);
        newGoal("Due soon", TODAY.plusDays(5), 5d, 10d, GoalStatus.IN_PROGRESS, false);
        newGoal("Already done", TODAY.plusDays(2), 10d, 10d, GoalStatus.COMPLETED, true);
        newGoal("Far off", TODAY.plusDays(90), 1d, 10d, GoalStatus.IN_PROGRESS, false);
        seedOpenYesterday();

        DailyBriefingResponseDTO briefing = briefingService.briefingFor(user, TODAY);

        assertThat(briefing.today().goalsApproaching())
                .extracting(goal -> goal.name())
                .containsExactly("Overdue", "Due soon");
        assertThat(briefing.today().goalsApproaching().get(0).daysRemaining()).isEqualTo(-3);
        assertThat(briefing.today().goalsApproaching().get(1).percentComplete()).isEqualTo(50);
    }

    /** The division that shipped as a bug in goalBox.tsx. On the server it would be a 500. */
    @Test
    void goalsApproaching_survivesAZeroTarget() {
        newGoal("No target", TODAY.plusDays(1), 3d, 0d, GoalStatus.IN_PROGRESS, false);
        seedOpenYesterday();

        DailyBriefingResponseDTO briefing = briefingService.briefingFor(user, TODAY);

        assertThat(briefing.today().goalsApproaching().get(0).percentComplete()).isZero();
    }

    // ---- worth showing ----

    /**
     * An account with no yesterday, no goals and nothing on today gets no dialog. A modal
     * that greets somebody every morning with "nothing happened" is how you teach them to
     * close it unread, and then it is dead on the mornings it matters.
     */
    @Test
    void anEmptyAccountIsNotWorthInterrupting() {
        User bare = newUser();
        authenticateAs(bare);

        DailyBriefingResponseDTO briefing = briefingService.briefingFor(bare, TODAY);

        assertThat(briefing.worthShowing()).isFalse();
        // And no row was created, so no model call could ever be paid for on this account.
        assertThat(briefingRepository.findByUserIdAndBriefingDate(bare.getId(), TODAY)).isEmpty();
    }

    /** Finishing a day is the result the product is about, so it earns the dialog. */
    @Test
    void aFullyResolvedYesterdayIsStillWorthShowing() {
        RoutineSnapshot snapshot = snapshotFor(YESTERDAY, true);
        addCheck(snapshot, "Done", check -> check.setChecked(true));
        persist(snapshot);

        DailyBriefingResponseDTO briefing = briefingService.briefingFor(user, TODAY);

        assertThat(briefing.worthShowing()).isTrue();
        assertThat(briefing.yesterday().fullyResolved()).isTrue();
        assertThat(briefing.yesterday().complete()).isTrue();
    }

    // ---- the row ----

    @Test
    void askingTwiceLeavesOneRow() {
        seedOpenYesterday();

        briefingService.briefingFor(user, TODAY);
        briefingService.briefingFor(user, TODAY);

        assertThat(briefingRepository.findAll().stream()
                .filter(row -> row.getUser().getId().equals(user.getId()))
                .toList()).hasSize(1);
    }

    /**
     * The reason this is a column and not local storage: closing the dialog has to hold
     * across devices, so it is read back off the row on the next request.
     */
    @Test
    void markSeen_survivesIntoTheNextRead() {
        seedOpenYesterday();
        briefingService.briefingFor(user, TODAY);

        assertThat(briefingService.briefingFor(user, TODAY).seenAt()).isNull();

        briefingService.markSeen(user, TODAY);

        assertThat(briefingService.briefingFor(user, TODAY).seenAt()).isNotNull();
    }

    /** With narration off the facts still answer in full, which is the fallback contract. */
    @Test
    void narrationOff_stillServesEveryFact() {
        seedOpenYesterday();

        DailyBriefingResponseDTO briefing = briefingService.briefingFor(user, TODAY);

        assertThat(briefing.narrative().status()).isEqualTo(NarrativeStatus.PENDING);
        assertThat(briefing.narrative().todayLines()).isEmpty();
        assertThat(briefing.yesterday().openItems()).isNotEmpty();
        assertThat(briefing.today().scheduledToday()).isTrue();
    }

    // ---- seeding ----

    private void seedOpenYesterday() {
        RoutineSnapshot snapshot = snapshotFor(YESTERDAY, false);
        addCheck(snapshot, "Something", check -> {});
        persist(snapshot);
    }

    private RoutineSnapshot snapshotFor(LocalDate date, boolean completed) {
        RoutineSnapshot snapshot = new RoutineSnapshot();
        snapshot.setRoutine(routine);
        snapshot.setUser(user);
        snapshot.setSnapshotDate(date);
        snapshot.setRoutineName(routine.getName());
        snapshot.setRoutineIconId(routine.getIconId());
        snapshot.setStructureJson("{\"sections\":[]}");
        snapshot.setCompleted(completed);
        snapshot.setChecks(new ArrayList<>());
        return snapshot;
    }

    private SnapshotCheck addCheck(RoutineSnapshot snapshot, String name,
                                   java.util.function.Consumer<SnapshotCheck> tweak) {
        SnapshotCheck check = new SnapshotCheck();
        check.setSnapshot(snapshot);
        check.setItemType(SnapshotItemType.HABIT);
        check.setItemName(name);
        check.setItemIconId("icon");
        check.setSectionName("Warm-up");
        check.setDifficulty(3);
        check.setImportance(3);
        tweak.accept(check);
        snapshot.getChecks().add(check);
        return check;
    }

    private void persist(RoutineSnapshot snapshot) {
        snapshotRepository.saveAndFlush(snapshot);
    }

    private void newGoal(String name, LocalDate endDate, double current, double target,
                         GoalStatus status, boolean complete) {
        Goal goal = new Goal();
        goal.setName(name);
        goal.setIconId("icon");
        goal.setTargetValue(target);
        goal.setCurrentValue(current);
        goal.setUnit("times");
        goal.setComplete(complete);
        goal.setStartDate(TODAY.minusDays(30));
        goal.setEndDate(endDate);
        goal.setXpReward(0);
        goal.setUser(user);
        goal.setStatus(status);
        goal.setTerm(GoalTerm.SHORT_TERM);
        goal.setCategories(new ArrayList<>());
        goalRepository.saveAndFlush(goal);
    }

    private User newUser() {
        User account = new User();
        account.setName("briefing");
        account.setEmail("briefing-" + UUID.randomUUID() + "@test.com");
        account.setPassword("password123");
        account.setGoogleAccount(false);
        account.setTimezone("UTC");
        account.setCompletedDays(new HashSet<>());
        account.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        account.setConstanceConfiguration(ConstanceConfiguration.ANY);
        account.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(account);
    }

    private DiaryRoutine newRoutine(User owner, Set<WeekDay> days) {
        Schedule schedule = new Schedule();
        schedule.setDays(days);
        schedule = scheduleRepository.saveAndFlush(schedule);

        Habit habit = new Habit();
        habit.setName("Seeded habit");
        habit.setIconId("icon");
        habit.setImportance(3);
        habit.setDificulty(3);
        habit.setDescription("d");
        habit.setMotivationalPhrase("m");
        habit.setCategories(new ArrayList<>());
        habit.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        habit.setUser(owner);
        habit = habitRepository.saveAndFlush(habit);

        DiaryRoutine created = new DiaryRoutine();
        created.setName("Morning");
        created.setIconId("icon");
        created.setUser(owner);
        created.setSchedule(schedule);
        created.setXpProgress(new XpProgress(0D, 0, 0D, 50D));

        RoutineSection section = new RoutineSection();
        section.setName("Warm-up");
        section.setIconId("icon");
        section.setStartTime(LocalTime.of(6, 0));
        section.setEndTime(LocalTime.of(7, 0));
        section.setOrderIndex(0);
        section.setFavorite(false);
        section.setRoutine(created);

        HabitGroup group = new HabitGroup();
        group.setHabit(habit);
        group.setRoutineSection(section);
        group.setStartTime(LocalTime.of(6, 0));
        group.setEndTime(LocalTime.of(6, 30));
        group.setHabitGroupChecks(new ArrayList<>());

        section.setHabitGroups(List.of(group));
        section.setTaskGroups(new ArrayList<>());
        created.setRoutineSections(List.of(section));

        DiaryRoutine saved = diaryRoutineRepository.saveAndFlush(created);
        return diaryRoutineRepository.findById(saved.getId()).orElseThrow();
    }

    /**
     * {@code SnapshotCheckService} reads the caller off the security context rather than
     * taking it as a parameter, so the half of {@code advertisedXp} that proves the payment
     * needs a context to run in.
     */
    private void authenticateAs(User account) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(account, null, List.of()));
    }
}
