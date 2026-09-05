package beyou.beyouapp.backend.integration.checkday;

import beyou.beyouapp.backend.domain.routine.RoutineType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R8/KTD24 — the deletion asymmetry, and R19 — the snapshot tables staying out of it.
 *
 * <p>Deleting the habit or the task takes its history with it. Deleting the routine it was
 * checked through, or editing that routine to drop it, leaves every row standing. The
 * asymmetry is the whole point: a routine edit that erased a habit's record is the failure
 * this feature exists to avoid, and it is what routine deletion does to snapshots today.
 *
 * <p>Everything here runs with {@code NOT_SUPPORTED} propagation, so each service call
 * supplies its own transaction and commits. That is not incidental — {@code deleteAllByOwner}
 * is a bulk {@code @Modifying} query with no transaction of its own, so a deletion path that
 * forgot to be transactional would throw {@code TransactionRequiredException} here rather
 * than quietly passing inside a test-owned transaction.
 */
class CheckDayDeletionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityCheckDayRepository entityCheckDayRepository;
    @Autowired private HabitService habitService;
    @Autowired private TaskService taskService;
    @Autowired private CategoryService categoryService;
    @Autowired private DiaryRoutineService diaryRoutineService;
    @Autowired private RoutineSnapshotRepository routineSnapshotRepository;
    @Autowired private SnapshotCheckRepository snapshotCheckRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private XpByLevelRepository xpByLevelRepository;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    private User user;
    private UUID categoryId;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Check Day Deletion IT User");
        user.setEmail("check-day-deletion-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);

        if (xpByLevelRepository.findByLevel(0) == null) xpByLevelRepository.save(new XpByLevel(0, 0));
        if (xpByLevelRepository.findByLevel(1) == null) xpByLevelRepository.save(new XpByLevel(1, 100));

        Category category = categoryService.createCategoryEntity(
                new CategoryRequestDTO("Health", "ic", null, ExperienceLevel.BEGINNER), user);
        categoryId = category.getId();
    }

    /**
     * These tests commit, so leftovers would outlive the class. Deleting the account is
     * enough for the history — {@code entity_check_day.user_id} cascades — but routines,
     * tasks and habits have to go first and in that order, exactly as
     * {@code RoutineGranularEditTest} documents.
     */
    @AfterEach
    void tearDown() {
        UUID userId = user.getId();
        diaryRoutineService.getAllDiaryRoutinesModels(userId)
                .forEach(routine -> diaryRoutineService.deleteDiaryRoutine(routine.getId(), userId));
        // Inside a transaction on purpose. `findAll` returns every snapshot check in the shared
        // Testcontainers database, including rows other test classes left behind, and
        // `getSnapshot().getUser()` walks a LAZY proxy: outside a session that is a
        // LazyInitializationException on whichever foreign row comes first, which is why this
        // teardown passed locally and failed in CI, where the class order differs.
        transactionTemplate.executeWithoutResult(status ->
                snapshotCheckRepository.deleteAll(snapshotCheckRepository.findAll().stream()
                        .filter(check -> check.getSnapshot() != null
                                && check.getSnapshot().getUser() != null
                                && userId.equals(check.getSnapshot().getUser().getId()))
                        .toList()));
        taskService.getAllTasks(userId).forEach(task -> taskService.deleteTask(task.id(), userId));
        habitService.getHabits(userId).forEach(habit -> habitService.deleteHabit(habit.id(), userId));
        userRepository.deleteById(userId);
    }

    // --- R8: deleting the entity takes its history --------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingAHabitRemovesItsHistoryAndLeavesEveryOtherOwnerAlone() {
        UUID doomed = createHabit("Read");
        UUID survivor = createHabit("Run");

        row(CheckDayOwnerType.HABIT, doomed, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.HABIT, doomed, DAY.plusDays(1), CheckDayOutcome.MISSED);
        row(CheckDayOwnerType.HABIT, survivor, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.USER, user.getId(), DAY, CheckDayOutcome.DONE);

        habitService.deleteHabit(doomed, user.getId());

        assertThat(history(CheckDayOwnerType.HABIT, doomed))
                .as("the deleted habit's history goes with it")
                .isEmpty();
        assertThat(history(CheckDayOwnerType.HABIT, survivor))
                .as("another habit's history is not collateral")
                .hasSize(1);
        assertThat(history(CheckDayOwnerType.USER, user.getId()))
                .as("the account's own streak history is not collateral either")
                .hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingATaskRemovesItsHistoryAndLeavesEveryOtherOwnerAlone() {
        UUID doomed = createTask("Estudar", false);
        UUID survivor = createTask("Escrever", false);

        row(CheckDayOwnerType.TASK, doomed, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.TASK, doomed, DAY.plusDays(1), CheckDayOutcome.SKIPPED);
        row(CheckDayOwnerType.TASK, survivor, DAY, CheckDayOutcome.DONE);

        taskService.deleteTask(doomed, user.getId());

        assertThat(history(CheckDayOwnerType.TASK, doomed)).isEmpty();
        assertThat(history(CheckDayOwnerType.TASK, survivor)).hasSize(1);
    }

    /**
     * Ids are unique per table, but the delete predicate is (owner type, owner id) — so a
     * habit and a task that somehow shared an id must still not take each other down.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingAHabitLeavesTheSameIdUnderAnotherOwnerTypeStanding() {
        UUID habitId = createHabit("Read");

        row(CheckDayOwnerType.HABIT, habitId, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.TASK, habitId, DAY, CheckDayOutcome.DONE);

        habitService.deleteHabit(habitId, user.getId());

        assertThat(history(CheckDayOwnerType.HABIT, habitId)).isEmpty();
        assertThat(history(CheckDayOwnerType.TASK, habitId)).hasSize(1);
    }

    // --- R8: the routine has no say -----------------------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingARoutineLeavesEveryHabitAndTaskHistoryStanding() {
        UUID habitId = createHabit("Read");
        UUID taskId = createTask("Estudar", false);
        Routine routine = createRoutineWith(habitId, taskId);

        row(CheckDayOwnerType.HABIT, habitId, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.TASK, taskId, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.ROUTINE, routine.routineId(), DAY, CheckDayOutcome.DONE);

        diaryRoutineService.deleteDiaryRoutine(routine.routineId(), user.getId());

        assertThat(history(CheckDayOwnerType.HABIT, habitId))
                .as("a habit outlives the routine it was checked through, and so does its record")
                .hasSize(1);
        assertThat(history(CheckDayOwnerType.TASK, taskId)).hasSize(1);
        assertThat(history(CheckDayOwnerType.ROUTINE, routine.routineId()))
                .as("even the routine's own history survives — a day's outcome is a fact about "
                        + "that day, not about a row that still exists")
                .hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void editingARoutineToRemoveAHabitLeavesThatHabitsHistoryStanding() {
        UUID habitId = createHabit("Read");
        Routine routine = createRoutineWith(habitId, null);

        row(CheckDayOwnerType.HABIT, habitId, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.HABIT, habitId, DAY.plusDays(1), CheckDayOutcome.DONE);

        diaryRoutineService.removeItemFromRoutine(
                routine.routineId(), routine.habitGroupId(), user.getId());

        assertThat(history(CheckDayOwnerType.HABIT, habitId))
                .as("a routine edit must never erase what the habit already did")
                .hasSize(2);
    }

    // --- R19: the snapshot tables are not part of this -----------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingAHabitLeavesItsSnapshotCheckRowsStanding() {
        UUID habitId = createHabit("Read");
        Routine routine = createRoutineWith(null, null);
        UUID snapshotCheckId = snapshotCheckFor(routine.routineId(), habitId);

        row(CheckDayOwnerType.HABIT, habitId, DAY, CheckDayOutcome.DONE);

        habitService.deleteHabit(habitId, user.getId());

        assertThat(history(CheckDayOwnerType.HABIT, habitId)).isEmpty();
        assertThat(snapshotCheckRepository.findById(snapshotCheckId))
                .as("this feature reads, writes and alters nothing in the snapshot tables")
                .isPresent();
    }

    // -- helpers --

    private record Routine(UUID routineId, UUID sectionId, UUID habitGroupId) {}

    private UUID createHabit(String name) {
        return habitService.createHabitEntity(
                new CreateHabitDTO(name, null, null, "ic", 3, 3, List.of(categoryId),
                        ExperienceLevel.BEGINNER),
                user.getId()).getId();
    }

    private UUID createTask(String name, boolean oneTime) {
        return taskService.createTaskEntity(
                new CreateTaskRequestDTO(name, null, "ic", 3, 3, List.of(), oneTime),
                user.getId()).getId();
    }

    private Routine createRoutineWith(UUID habitId, UUID taskId) {
        DiaryRoutineResponseDTO created = diaryRoutineService.createDiaryRoutine(
                new DiaryRoutineRequestDTO("R", "", RoutineType.DAILY, List.of(new RoutineSectionRequestDTO(
                        null, "Morning", "ic", LocalTime.of(6, 0), LocalTime.of(9, 0),
                        List.of(), List.of(), false)), List.of()),
                user);
        UUID routineId = created.id();
        UUID sectionId = created.routineSections().get(0).id();

        // The add's own response carries the generated group id (the service flushes
        // before mapping since the null-id fix); RoutineGranularEditTest guards that.
        UUID habitGroupId = null;
        if (habitId != null) {
            habitGroupId = diaryRoutineService.addHabitToSection(
                    routineId, sectionId, habitId, LocalTime.of(7, 0), LocalTime.of(7, 30),
                    user.getId())
                    .routineSections().get(0).habitGroup().get(0).id();
        }
        if (taskId != null) {
            diaryRoutineService.addTaskToSection(
                    routineId, sectionId, taskId, LocalTime.of(6, 0), LocalTime.of(6, 30),
                    user.getId());
        }
        return new Routine(routineId, sectionId, habitGroupId);
    }

    private UUID snapshotCheckFor(UUID routineId, UUID originalItemId) {
        RoutineSnapshot snapshot = new RoutineSnapshot();
        snapshot.setRoutine(diaryRoutineService.getDiaryRoutineModelById(routineId, user.getId()));
        snapshot.setUser(user);
        snapshot.setSnapshotDate(DAY);
        snapshot.setRoutineName("R");
        snapshot.setStructureJson("[]");
        snapshot.setCompleted(false);

        SnapshotCheck check = new SnapshotCheck();
        check.setSnapshot(snapshot);
        check.setItemType(SnapshotItemType.HABIT);
        check.setItemName("Read");
        check.setSectionName("Morning");
        check.setOriginalItemId(originalItemId);
        check.setDifficulty(3);
        check.setImportance(3);
        snapshot.getChecks().add(check);
        routineSnapshotRepository.saveAndFlush(snapshot);
        return check.getId();
    }

    private void row(CheckDayOwnerType type, UUID ownerId, LocalDate day, CheckDayOutcome outcome) {
        entityCheckDayRepository.saveAndFlush(
                new EntityCheckDay(user, type, ownerId, day, outcome));
    }

    private List<EntityCheckDay> history(CheckDayOwnerType type, UUID ownerId) {
        return entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(type, ownerId);
    }
}
