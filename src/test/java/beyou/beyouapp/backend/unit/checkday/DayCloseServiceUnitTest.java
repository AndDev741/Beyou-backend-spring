package beyou.beyouapp.backend.unit.checkday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckDayRecorder;
import beyou.beyouapp.backend.domain.checkday.DayCloseService;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * The day-close pass, exercised against a fake of the one Postgres statement it issues.
 *
 * <p>{@code INSERT ... ON CONFLICT DO NOTHING} has no JPA equivalent, so the write goes
 * through {@code EntityManager} and the test stands in for the table: every accepted insert
 * is mirrored into {@link #recorded}, which is what a second pass would read back. That
 * makes "writes nothing the second time" an assertion about statements issued rather than
 * about scalars happening to land on the same values.
 */
@ExtendWith(MockitoExtension.class)
class DayCloseServiceUnitTest {

    /** 2026-03-19 is a Thursday. The pass runs on the 20th and closes it. */
    private static final LocalDate CLOSING_DAY = LocalDate.of(2026, 3, 19);

    private static final LocalDate ACCOUNT_CREATED = LocalDate.of(2026, 1, 1);

    @Mock
    private EntityCheckDayRepository entityCheckDayRepository;

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private DiaryRoutineRepository diaryRoutineRepository;

    @Mock
    private UserCacheEvictService userCacheEvictService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query insertStatement;

    @InjectMocks
    private DayCloseService dayCloseService;

    private User user;
    private UUID userId;

    /** Rows the fake table has accepted, in insert order. */
    private final List<EntityCheckDay> recorded = new ArrayList<>();

    /** Parameters bound so far for the statement currently being built. */
    private final Map<String, String> pendingParameters = new HashMap<>();

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setTimezone("UTC");
        user.setCreatedAt(Date.valueOf(ACCOUNT_CREATED));

        // Shared baseline: an account with nothing on it. Each test overrides the one or two
        // collections it cares about, so these are lenient by design.
        lenient().when(entityManager.find(User.class, userId)).thenReturn(user);
        lenient().when(habitRepository.findAllByUserId(userId)).thenReturn(new ArrayList<>());
        lenient().when(taskRepository.findAllByUserId(userId)).thenReturn(Optional.of(List.of()));
        lenient().when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of());
    }

    // ---------------------------------------------------------------
    // Idempotency — KTD18
    // ---------------------------------------------------------------

    @Test
    void runningTheSameDayTwiceWritesNothingTheSecondTime() {
        givenHabits(habit("Read", ACCOUNT_CREATED));
        givenInsertsSucceed();

        int first = dayCloseService.closeDay(user, CLOSING_DAY);
        assertThat(first).as("the habit and the account itself").isEqualTo(2);

        // Hand the second pass exactly what the first one wrote.
        givenAlreadyRecorded(recorded);
        clearInvocations(entityManager, insertStatement, userCacheEvictService);
        when(entityManager.find(User.class, userId)).thenReturn(user);

        int second = dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(second).isZero();
        verify(entityManager, never()).createNativeQuery(anyString());
        verify(userCacheEvictService, never()).evictUserScopedCaches(any());
    }

    @Test
    void aDoneRowFromTheRequestPathIsLeftAloneWhenThePassRunsAfterwards() {
        Habit habit = habit("Read", ACCOUNT_CREATED);
        givenHabits(habit);
        givenRoutines(routineCovering(habit, WeekDay.Thursday));
        givenAlreadyRecorded(List.of(
                row(CheckDayOwnerType.HABIT, habit.getId(), CLOSING_DAY, CheckDayOutcome.DONE)));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOutcomes())
                .as("the habit already had its day settled — the pass must not touch that row")
                .doesNotContainKey(habit.getId())
                .containsKey(userId);
    }

    @Test
    void aCheckThatCommitsAfterThePassLosesTheInsertRaceAndKeepsItsScalars() {
        // The request path took the row between the diff and the insert, so ON CONFLICT
        // absorbs ours. That writer recomputed already — we must not walk over its work.
        Habit habit = habit("Read", ACCOUNT_CREATED);
        habit.getCheckProgress().setCurrentStreak(4);
        habit.getCheckProgress().setTotalCheckIns(9);
        givenHabits(habit);
        givenRoutines(routineCovering(habit, WeekDay.Thursday));
        givenInsertsRejected();

        int written = dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(written).isZero();
        assertThat(habit.getCheckProgress().getCurrentStreak()).isEqualTo(4);
        assertThat(habit.getCheckProgress().getTotalCheckIns()).isEqualTo(9);
        verify(userCacheEvictService, never()).evictUserScopedCaches(any());
    }

    // ---------------------------------------------------------------
    // Which outcome each owner gets — R6, R11
    // ---------------------------------------------------------------

    @Test
    void aHabitScheduledForTheDayAndNeverCheckedIsMissedAndItsStreakDrops() {
        Habit habit = habit("Read", ACCOUNT_CREATED);
        habit.getCheckProgress().setCurrentStreak(6);
        habit.getCheckProgress().setBestStreak(6);
        givenHabits(habit);
        givenRoutines(routineCovering(habit, WeekDay.Thursday));
        givenHistory(habit.getId(),
                row(CheckDayOwnerType.HABIT, habit.getId(), CLOSING_DAY.minusDays(1), CheckDayOutcome.DONE));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOutcomes()).containsEntry(habit.getId(), CheckDayOutcome.MISSED);
        assertThat(habit.getCheckProgress().getCurrentStreak())
                .as("the walk back from today hits the missed day immediately")
                .isZero();
        assertThat(habit.getCheckProgress().getBestStreak())
                .as("R13 — the record never falls")
                .isEqualTo(6);
    }

    @Test
    void aHabitOffSchedulleForTheDayIsNotScheduledAndKeepsItsStreak() {
        Habit habit = habit("Gym", ACCOUNT_CREATED);
        givenHabits(habit);
        givenRoutines(routineCovering(habit, WeekDay.Monday, WeekDay.Wednesday, WeekDay.Friday));
        givenHistory(habit.getId(),
                row(CheckDayOwnerType.HABIT, habit.getId(), CLOSING_DAY.minusDays(2), CheckDayOutcome.DONE),
                row(CheckDayOwnerType.HABIT, habit.getId(), CLOSING_DAY.minusDays(1), CheckDayOutcome.DONE));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOutcomes()).containsEntry(habit.getId(), CheckDayOutcome.NOT_SCHEDULED);
        assertThat(habit.getCheckProgress().getCurrentStreak())
                .as("a Thursday a Mon/Wed/Fri habit was never asked about cannot break it")
                .isEqualTo(2);
    }

    @Test
    void aHabitInNoRoutineIsNotInRoutineAndKeepsItsStreak() {
        Habit habit = habit("Journal", ACCOUNT_CREATED);
        givenHabits(habit);
        givenRoutines(routineCovering(null, WeekDay.Thursday));
        givenHistory(habit.getId(),
                row(CheckDayOwnerType.HABIT, habit.getId(), CLOSING_DAY.minusDays(1), CheckDayOutcome.DONE));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOutcomes()).containsEntry(habit.getId(), CheckDayOutcome.NOT_IN_ROUTINE);
        assertThat(habit.getCheckProgress().getCurrentStreak()).isEqualTo(1);
    }

    @Test
    void aHabitInARoutineWithNoScheduleIsNotScheduledRatherThanMissed() {
        Habit habit = habit("Read", ACCOUNT_CREATED);
        givenHabits(habit);
        DiaryRoutine routine = routineCovering(habit, WeekDay.Thursday);
        routine.setSchedule(null);
        givenRoutines(routine);
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOutcomes())
                .as("a routine that never runs cannot be missed")
                .containsEntry(habit.getId(), CheckDayOutcome.NOT_SCHEDULED);
    }

    @Test
    void aHabitInTwoRoutinesGetsExactlyOneRow() {
        Habit habit = habit("Read", ACCOUNT_CREATED);
        givenHabits(habit);
        givenRoutines(
                routineCovering(habit, WeekDay.Monday),
                routineCovering(habit, WeekDay.Thursday));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOwnerIds(CheckDayOwnerType.HABIT))
                .as("one habit is one owner, however many routines hold it")
                .containsExactly(habit.getId());
        assertThat(insertedOutcomes())
                .as("scheduled by either routine is scheduled")
                .containsEntry(habit.getId(), CheckDayOutcome.MISSED);
    }

    // ---------------------------------------------------------------
    // Which owners are closed at all — R4, existence floor
    // ---------------------------------------------------------------

    @Test
    void oneTimeTasksAreSkippedEntirely() {
        Task recurring = task("Water plants", ACCOUNT_CREATED, false);
        Task oneOff = task("Renew passport", ACCOUNT_CREATED, true);
        givenTasks(recurring, oneOff);
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOwnerIds(CheckDayOwnerType.TASK)).containsExactly(recurring.getId());
    }

    @Test
    void aHabitCreatedAfterTheClosingDayGetsNoRowAtAll() {
        Habit older = habit("Read", CLOSING_DAY);
        Habit newborn = habit("Meditate", CLOSING_DAY.plusDays(1));
        givenHabits(older, newborn);
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOwnerIds(CheckDayOwnerType.HABIT))
                .as("stamping a day the habit did not exist for would invent history")
                .containsExactly(older.getId());
    }

    @Test
    void anAccountCreatedAfterTheClosingDayClosesNothing() {
        User youngAccount = new User();
        youngAccount.setId(userId);
        youngAccount.setTimezone("UTC");
        youngAccount.setCreatedAt(Date.valueOf(CLOSING_DAY.plusDays(1)));
        when(entityManager.find(User.class, userId)).thenReturn(youngAccount);
        givenRoutines(routineCovering(null, WeekDay.Thursday));
        givenInsertsSucceed();

        int written = dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(written)
                .as("an account cannot have missed a day it did not exist for")
                .isZero();
    }

    @Test
    void routinesGetNoRowOfTheirOwn() {
        Habit habit = habit("Read", ACCOUNT_CREATED);
        givenHabits(habit);
        givenRoutines(routineCovering(habit, WeekDay.Thursday));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOwnerIds(CheckDayOwnerType.ROUTINE))
                .as("nothing writes a routine a presence outcome, so the only rows it could "
                        + "ever collect are absences — a wrong row is worse than no row")
                .isEmpty();
    }

    // ---------------------------------------------------------------
    // The account-level row
    // ---------------------------------------------------------------

    @Test
    void theAccountRowIsDoneOnADayTheUserCompleted() {
        user.setCompletedDays(new HashSet<>(Set.of(CLOSING_DAY)));
        givenRoutines(routineCovering(null, WeekDay.Thursday));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOutcomes())
                .as("the user finished that Thursday — GET /check-history?ownerType=USER must "
                        + "not report it as an absence")
                .containsEntry(userId, CheckDayOutcome.DONE);
    }

    @Test
    void theAccountsOwnScalarsCountTheDaysItCompleted() {
        user.setCompletedDays(new HashSet<>(Set.of(CLOSING_DAY)));
        givenRoutines(routineCovering(null, WeekDay.Thursday));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(user.getCheckProgress().getTotalCheckIns())
                .as("users.check_total_check_ins is rewritten nightly from rows that never "
                        + "said DONE, so it sat permanently at zero")
                .isEqualTo(1);
    }

    @Test
    void theAccountRowIsStillAnAbsenceOnADayTheUserDidNotComplete() {
        user.setCompletedDays(new HashSet<>(Set.of(CLOSING_DAY.minusDays(1))));
        givenRoutines(routineCovering(null, WeekDay.Thursday));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOutcomes()).containsEntry(userId, CheckDayOutcome.MISSED);
    }

    @Test
    void theUserRowCarriesWhetherAnyRoutineWasScheduled() {
        givenRoutines(routineCovering(null, WeekDay.Thursday));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOutcomes())
                .as("a routine ran that Thursday and the account showed nothing for it")
                .containsEntry(userId, CheckDayOutcome.MISSED);
    }

    @Test
    void theUserRowIsNotScheduledWhenNoRoutineCoversTheDay() {
        givenRoutines(routineCovering(null, WeekDay.Monday));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(insertedOutcomes()).containsEntry(userId, CheckDayOutcome.NOT_SCHEDULED);
    }

    @Test
    void aUserWithNoRoutinesGetsANotInRoutineRowAndNoException() {
        givenInsertsSucceed();

        int written = dayCloseService.closeDay(user, CLOSING_DAY);

        assertThat(written).isEqualTo(1);
        assertThat(insertedOutcomes()).containsExactly(
                java.util.Map.entry(userId, CheckDayOutcome.NOT_IN_ROUTINE));
    }

    // ---------------------------------------------------------------
    // Locking — KTD26
    // ---------------------------------------------------------------

    @Test
    void thePassTakesTheOwnerLockBeforeItReadsTheHistoryItRecomputesFrom() {
        // The pass writes yesterday while the request path writes today. Different unique
        // keys, so ON CONFLICT never fires between them and nothing serialises the two
        // recomputes of the same habit's scalars — the pass commits its stale streak last.
        Habit habit = habit("Read", ACCOUNT_CREATED);
        givenHabits(habit);
        givenRoutines(routineCovering(habit, WeekDay.Thursday));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        InOrder order = inOrder(entityCheckDayRepository);
        order.verify(entityCheckDayRepository, times(2)).lockCheckOwner(anyInt(), anyInt());
        order.verify(entityCheckDayRepository)
                .findByOwnerTypeAndOwnerIdOrderByDayAsc(CheckDayOwnerType.HABIT, habit.getId());
    }

    @Test
    void thePassAndTheRecorderDeriveTheSameLockKeysInTheSameOrder() {
        // Two writers taking different keys for the same owner is a lock that protects
        // nothing, so the derivation has to be one piece of code, not two copies.
        Habit habit = habit("Read", ACCOUNT_CREATED);
        givenHabits(habit);
        givenRoutines(routineCovering(habit, WeekDay.Thursday));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);
        List<LockKey> takenByThePass = capturedLockKeys();

        clearInvocations(entityCheckDayRepository);
        when(entityCheckDayRepository.save(any(EntityCheckDay.class))).thenAnswer(i -> i.getArgument(0));
        new CheckDayRecorder(entityCheckDayRepository).record(
                user, CheckDayOwnerType.HABIT, habit.getId(), new CheckProgress(),
                CLOSING_DAY, CheckDayOutcome.DONE);
        List<LockKey> takenByTheRecorder = capturedLockKeys();

        assertThat(takenByTheRecorder).hasSize(2);
        assertThat(takenByThePass)
                .as("the pass visits the habit first, and must take exactly the pair the "
                        + "request path takes for it")
                .startsWith(takenByTheRecorder.toArray(new LockKey[0]));
    }

    private record LockKey(int classId, int objectId) {}

    private List<LockKey> capturedLockKeys() {
        ArgumentCaptor<Integer> classIds = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> objectIds = ArgumentCaptor.forClass(Integer.class);
        verify(entityCheckDayRepository, org.mockito.Mockito.atLeastOnce())
                .lockCheckOwner(classIds.capture(), objectIds.capture());
        List<LockKey> keys = new ArrayList<>();
        for (int i = 0; i < classIds.getAllValues().size(); i++) {
            keys.add(new LockKey(classIds.getAllValues().get(i), objectIds.getAllValues().get(i)));
        }
        return keys;
    }

    // ---------------------------------------------------------------
    // Surrounding contract
    // ---------------------------------------------------------------

    @Test
    void theUserScopedCachesAreDroppedOnceWhenAnythingWasWritten() {
        givenHabits(habit("Read", ACCOUNT_CREATED), habit("Gym", ACCOUNT_CREATED));
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        verify(userCacheEvictService).evictUserScopedCaches(userId);
        verify(userCacheEvictService, never()).evictAllUserCaches(any());
        verify(userCacheEvictService, never()).clearSharedRoutineCache();
    }

    @Test
    void liveHabitAndTaskCheckRowsAreNeverConsulted() {
        // SnapshotCheckMigrator deletes those rows once they are copied into the snapshot.
        // A pass that re-derived presence from them would see an empty day and stamp MISSED
        // over a real DONE, so the only presence evidence is entity_check_day.
        Habit habit = habit("Read", ACCOUNT_CREATED);
        givenHabits(habit);
        DiaryRoutine routine = routineCovering(habit, WeekDay.Thursday);
        givenRoutines(routine);
        givenInsertsSucceed();

        dayCloseService.closeDay(user, CLOSING_DAY);

        HabitGroup group = routine.getRoutineSections().get(0).getHabitGroups().get(0);
        assertThat(group.getHabitGroupChecks())
                .as("nothing in the pass touches the live check collections")
                .isEmpty();
    }

    @Test
    void aMissingUserOrDayIsRejectedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> dayCloseService.closeDay(null, CLOSING_DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dayCloseService.closeDay(new User(), CLOSING_DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dayCloseService.closeDay(user, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    void anAccountDeletedBetweenTheListingAndThePassClosesNothing() {
        when(entityManager.find(User.class, userId)).thenReturn(null);

        assertThat(dayCloseService.closeDay(user, CLOSING_DAY)).isZero();
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private void givenHabits(Habit... habits) {
        lenient().when(habitRepository.findAllByUserId(userId))
                .thenReturn(new ArrayList<>(List.of(habits)));
    }

    private void givenTasks(Task... tasks) {
        lenient().when(taskRepository.findAllByUserId(userId)).thenReturn(Optional.of(List.of(tasks)));
    }

    private void givenRoutines(DiaryRoutine... routines) {
        lenient().when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routines));
    }

    private void givenAlreadyRecorded(List<EntityCheckDay> rows) {
        lenient().when(entityCheckDayRepository.findByUserIdAndDay(userId, CLOSING_DAY))
                .thenReturn(new ArrayList<>(rows));
    }

    private void givenHistory(UUID ownerId, EntityCheckDay... rows) {
        lenient().when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                CheckDayOwnerType.HABIT, ownerId)).thenReturn(new ArrayList<>(List.of(rows)));
    }

    private Habit habit(String name, LocalDate createdOn) {
        Habit habit = new Habit();
        habit.setId(UUID.randomUUID());
        habit.setName(name);
        habit.setUser(user);
        habit.setCreatedAt(Date.valueOf(createdOn));
        return habit;
    }

    private Task task(String name, LocalDate createdOn, boolean oneTime) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setName(name);
        task.setUser(user);
        task.setOneTimeTask(oneTime);
        task.setCreatedAt(Date.valueOf(createdOn));
        return task;
    }

    /** A routine holding {@code habit} (or nothing, when null) and running on {@code days}. */
    private DiaryRoutine routineCovering(Habit habit, WeekDay... days) {
        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(UUID.randomUUID());
        routine.setName("Routine");
        routine.setUser(user);

        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(days));
        routine.setSchedule(schedule);

        RoutineSection section = new RoutineSection();
        section.setId(UUID.randomUUID());
        section.setRoutine(routine);
        section.setTaskGroups(new ArrayList<>());
        List<HabitGroup> habitGroups = new ArrayList<>();
        if (habit != null) {
            HabitGroup group = new HabitGroup();
            group.setId(UUID.randomUUID());
            group.setHabit(habit);
            group.setRoutineSection(section);
            group.setHabitGroupChecks(new ArrayList<>());
            habitGroups.add(group);
        }
        section.setHabitGroups(habitGroups);
        routine.setRoutineSections(new ArrayList<>(List.of(section)));
        return routine;
    }

    private EntityCheckDay row(CheckDayOwnerType type, UUID ownerId, LocalDate day, CheckDayOutcome outcome) {
        return new EntityCheckDay(user, type, ownerId, day, outcome);
    }

    /**
     * Stands in for the Postgres statement, accepting every insert. Each one is mirrored
     * into {@link #recorded}, so a second pass can be handed back exactly what the first
     * pass wrote.
     */
    private void givenInsertsSucceed() {
        givenInserts(1);
    }

    /** The same, but every insert loses to a row that appeared first. */
    private void givenInsertsRejected() {
        givenInserts(0);
    }

    private void givenInserts(int rowsAffected) {
        // Lenient: several tests assert that NO statement is issued at all.
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(insertStatement);
        lenient().when(insertStatement.setParameter(anyString(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            pendingParameters.put(name, String.valueOf(value));
            return insertStatement;
        });
        lenient().when(insertStatement.executeUpdate()).thenAnswer(invocation -> {
            if (rowsAffected == 1) {
                recorded.add(new EntityCheckDay(
                        user,
                        CheckDayOwnerType.valueOf(pendingParameters.get("ownerType")),
                        UUID.fromString(pendingParameters.get("ownerId")),
                        LocalDate.parse(pendingParameters.get("day")),
                        CheckDayOutcome.valueOf(pendingParameters.get("outcome"))));
            }
            pendingParameters.clear();
            return rowsAffected;
        });
    }

    /** Owner id to outcome for everything the pass wrote, in insert order. */
    private Map<UUID, CheckDayOutcome> insertedOutcomes() {
        Map<UUID, CheckDayOutcome> byOwner = new LinkedHashMap<>();
        recorded.forEach(row -> byOwner.put(row.getOwnerId(), row.getOutcome()));
        return byOwner;
    }

    private List<UUID> insertedOwnerIds(CheckDayOwnerType type) {
        return recorded.stream()
                .filter(row -> row.getOwnerType() == type)
                .map(EntityCheckDay::getOwnerId)
                .toList();
    }
}
