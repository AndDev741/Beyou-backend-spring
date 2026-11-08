package beyou.beyouapp.backend.integration.routine;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.RoutineType;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO.RoutineSectionResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineItemRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.HabitGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-item weekdays (V43), end to end through the real services and database.
 *
 * <p>The scenario is the one that asked for the feature: a routine that runs on two days,
 * holding an item that belongs on only one of them. "Today" is the owner's today and the
 * other day is the next weekday, so every test runs the same on any date: the routine is
 * scheduled on both, the "fish tank" habit is limited to the other day, and today it must
 * vanish from everything that describes today while staying in the routine itself.
 */
class RoutineItemDaysIntegrationTest extends AbstractIntegrationTest {

    @Autowired private DiaryRoutineService diaryRoutineService;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private ScheduleService scheduleService;
    @Autowired private CategoryService categoryService;
    @Autowired private HabitService habitService;
    @Autowired private TaskService taskService;
    @Autowired private UserRepository userRepository;
    @Autowired private XpByLevelRepository xpByLevelRepository;
    @Autowired private SnapshotService snapshotService;
    @Autowired private TransactionTemplate transactionTemplate;

    private User user;
    private UUID meditateId;
    private UUID fishTankId;
    private UUID taskId;
    private LocalDate today;
    private WeekDay todayWeekDay;
    private WeekDay otherWeekDay;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Item Days IT User");
        user.setEmail("item-days-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
        // After the first save: User's @PrePersist resets the mode to ANY, and under ANY the
        // COMPLETE test below would pass whether or not the other-day items were counted.
        user.setConstanceConfiguration(ConstanceConfiguration.COMPLETE);
        user = userRepository.saveAndFlush(user);
        if (xpByLevelRepository.findByLevel(0) == null) xpByLevelRepository.save(new XpByLevel(0, 0));
        if (xpByLevelRepository.findByLevel(1) == null) xpByLevelRepository.save(new XpByLevel(1, 100));

        Category category = categoryService.createCategoryEntity(
                new CategoryRequestDTO("Home", "ic", null, ExperienceLevel.BEGINNER), user);
        meditateId = habitService.createHabitEntity(
                new CreateHabitDTO("Meditate", null, null, "ic", 3, 3, List.of(category.getId()),
                        ExperienceLevel.BEGINNER), user.getId()).getId();
        fishTankId = habitService.createHabitEntity(
                new CreateHabitDTO("Clean the fish tank", null, null, "ic", 3, 3, List.of(category.getId()),
                        ExperienceLevel.BEGINNER), user.getId()).getId();
        taskId = taskService.createTaskEntity(
                new CreateTaskRequestDTO("Water the plants", null, "ic", 3, 3, List.of(), false),
                user.getId()).getId();

        today = UserDateResolver.today(user);
        todayWeekDay = ScheduledOnDayResolver.weekDayOf(today);
        otherWeekDay = ScheduledOnDayResolver.weekDayOf(today.plusDays(1));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static HabitGroupDTO habit(UUID id, UUID habitId, Set<WeekDay> days) {
        return new HabitGroupDTO(id, habitId, LocalTime.of(7, 10), LocalTime.of(7, 20), null, days);
    }

    private static RoutineSectionRequestDTO section(UUID id, String name, List<HabitGroupDTO> habits,
                                                    List<TaskGroupDTO> tasks) {
        return new RoutineSectionRequestDTO(id, name, "ic", LocalTime.of(7, 0), LocalTime.of(8, 0),
                tasks, habits, false);
    }

    private DiaryRoutineRequestDTO daily(List<RoutineSectionRequestDTO> sections) {
        return new DiaryRoutineRequestDTO("Weekend", "ic", RoutineType.DAILY, sections, null);
    }

    /**
     * Section "Morning": meditate (every day) + fish tank (other day only).
     * Section "Chores": the task, other day only, so today leaves the section empty.
     */
    private DiaryRoutineResponseDTO createWeekendRoutine() {
        DiaryRoutineResponseDTO created = diaryRoutineService.createDiaryRoutine(daily(List.of(
                section(null, "Morning", List.of(
                        habit(null, meditateId, null),
                        habit(null, fishTankId, Set.of(otherWeekDay))), List.of()),
                section(null, "Chores", List.of(), List.of(
                        new TaskGroupDTO(null, taskId, LocalTime.of(7, 30), LocalTime.of(7, 40), null,
                                Set.of(otherWeekDay)))))), user.getId());
        scheduleService.create(new CreateScheduleDTO(Set.of(todayWeekDay, otherWeekDay), created.id()), user.getId());
        return created;
    }

    private static RoutineSectionResponseDTO.HabitGroupResponseDTO habitGroupOf(
            DiaryRoutineResponseDTO routine, UUID habitId) {
        return routine.routineSections().stream()
                .flatMap(section -> section.habitGroup().stream())
                .filter(group -> group.habitId().equals(habitId))
                .findFirst().orElseThrow();
    }

    private CheckGroupRequestDTO checkOf(UUID routineId, UUID habitGroupId) {
        return new CheckGroupRequestDTO(routineId, null, new HabitGroupRequestDTO(habitGroupId, null), today);
    }

    // ── what today looks like ────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void todayServesOnlyTheItemsThatRunToday() {
        UUID routineId = createWeekendRoutine().id();

        DiaryRoutineResponseDTO todays = diaryRoutineService.getTodayRoutineScheduled(user.getId(), today);

        assertNotNull(todays, "the routine still runs today: meditate is on it");
        assertEquals(routineId, todays.id());
        assertEquals(1, todays.routineSections().size(), "Chores only held an other-day item, so it is gone");
        RoutineSectionResponseDTO morning = todays.routineSections().get(0);
        assertEquals("Morning", morning.name());
        assertEquals(List.of(meditateId),
                morning.habitGroup().stream().map(RoutineSectionResponseDTO.HabitGroupResponseDTO::habitId).toList());

        // The other day sees the full routine, fish tank and task included.
        DiaryRoutineResponseDTO tomorrow = diaryRoutineService.getTodayRoutineScheduled(user.getId(), today.plusDays(1));
        assertEquals(2, tomorrow.routineSections().size());
        assertEquals(2, tomorrow.routineSections().get(0).habitGroup().size());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theRoutineItselfKeepsEveryItemWithItsDays() {
        UUID routineId = createWeekendRoutine().id();

        DiaryRoutineResponseDTO full = diaryRoutineService.getDiaryRoutineById(routineId, user.getId());

        assertEquals(2, full.routineSections().size());
        assertEquals(Set.of(otherWeekDay), habitGroupOf(full, fishTankId).days());
        assertTrue(habitGroupOf(full, meditateId).days().isEmpty(), "no days of its own: follows the routine");
        assertEquals(Set.of(otherWeekDay), full.routineSections().get(1).taskGroup().get(0).days());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRoutineHoldingOnlyOtherDayItemsIsNotTodaysRoutine() {
        DiaryRoutineResponseDTO created = diaryRoutineService.createDiaryRoutine(daily(List.of(
                section(null, "Morning", List.of(habit(null, fishTankId, Set.of(otherWeekDay))), List.of()))),
                user.getId());
        scheduleService.create(new CreateScheduleDTO(Set.of(todayWeekDay, otherWeekDay), created.id()), user.getId());

        assertNull(diaryRoutineService.getTodayRoutineScheduled(user.getId(), today),
                "an empty routine is not today's routine");
    }

    // ── writes on a day the item does not run ────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void checkingOrSkippingAnItemOnADayItsDaysLeaveOutIsRefused() {
        DiaryRoutineResponseDTO created = createWeekendRoutine();
        UUID fishTankGroupId = habitGroupOf(created, fishTankId).id();

        BusinessException checkError = assertThrows(BusinessException.class,
                () -> diaryRoutineService.checkAndUncheckGroup(checkOf(created.id(), fishTankGroupId), user.getId()));
        assertEquals(ErrorKey.ROUTINE_ITEM_NOT_ACTIVE_ON_DAY, checkError.getErrorKey());

        BusinessException skipError = assertThrows(BusinessException.class,
                () -> diaryRoutineService.skipOrUnskipGroup(new SkipGroupRequestDTO(created.id(), null,
                        new HabitGroupRequestDTO(fishTankGroupId, null), today, true), user.getId()));
        assertEquals(ErrorKey.ROUTINE_ITEM_NOT_ACTIVE_ON_DAY, skipError.getErrorKey());
    }

    /** A check made before the item was limited can still be taken back the same day. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void uncheckingStaysOpenAfterTheItemIsLimitedAway() {
        DiaryRoutineResponseDTO created = createWeekendRoutine();
        UUID meditateGroupId = habitGroupOf(created, meditateId).id();
        diaryRoutineService.checkAndUncheckGroup(checkOf(created.id(), meditateGroupId), user.getId());

        diaryRoutineService.setItemDays(created.id(), meditateGroupId, Set.of(otherWeekDay), user.getId());
        diaryRoutineService.checkAndUncheckGroup(checkOf(created.id(), meditateGroupId), user.getId());

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(created.id()).orElseThrow();
            boolean checkedToday = routine.getRoutineSections().stream()
                    .flatMap(section -> section.getHabitGroups().stream())
                    .filter(group -> group.getId().equals(meditateGroupId))
                    .flatMap(group -> group.getHabitGroupChecks().stream())
                    .anyMatch(check -> check.getCheckDate().equals(today) && check.isChecked());
            assertTrue(!checkedToday, "the toggle went through as an uncheck");
        });
    }

    // ── editing ──────────────────────────────────────────────────────────

    /**
     * An older client sends the routine back without the field. That must not wipe the days,
     * and an explicit empty set must clear them.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anUpdateWithoutDaysKeepsThemAndAnEmptySetClearsThem() {
        DiaryRoutineResponseDTO created = createWeekendRoutine();
        RoutineSectionResponseDTO morning = created.routineSections().get(0);
        UUID meditateGroupId = habitGroupOf(created, meditateId).id();
        UUID fishTankGroupId = habitGroupOf(created, fishTankId).id();

        DiaryRoutineResponseDTO kept = diaryRoutineService.updateDiaryRoutine(created.id(), daily(List.of(
                section(morning.id(), "Morning", List.of(
                        habit(meditateGroupId, meditateId, null),
                        habit(fishTankGroupId, fishTankId, null)), List.of()))), user.getId());
        assertEquals(Set.of(otherWeekDay), habitGroupOf(kept, fishTankId).days(), "null keeps");

        DiaryRoutineResponseDTO cleared = diaryRoutineService.updateDiaryRoutine(created.id(), daily(List.of(
                section(morning.id(), "Morning", List.of(
                        habit(meditateGroupId, meditateId, null),
                        habit(fishTankGroupId, fishTankId, Set.of())), List.of()))), user.getId());
        assertTrue(habitGroupOf(cleared, fishTankId).days().isEmpty(), "an empty set clears");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aListRoutineFiltersItsItemsTheSameWay() {
        DiaryRoutineResponseDTO created = diaryRoutineService.createDiaryRoutine(
                new DiaryRoutineRequestDTO("Errands", "ic", RoutineType.LIST, null, List.of(
                        new RoutineItemRequestDTO(null, meditateId, null, null),
                        new RoutineItemRequestDTO(null, fishTankId, null, Set.of(otherWeekDay)))),
                user.getId());
        scheduleService.create(new CreateScheduleDTO(Set.of(todayWeekDay, otherWeekDay), created.id()), user.getId());
        assertEquals(Set.of(otherWeekDay), created.items().get(1).days());

        DiaryRoutineResponseDTO todays = diaryRoutineService.getTodayRoutineScheduled(user.getId(), today);

        assertEquals(List.of(meditateId), todays.items().stream()
                .map(DiaryRoutineResponseDTO.RoutineItemResponseDTO::habitId).toList());
        assertEquals(1, todays.routineSections().get(0).habitGroup().size(),
                "the section view a pre-List client reads is filtered too");
    }

    // ── the day's record ─────────────────────────────────────────────────

    /**
     * COMPLETE mode: every item that runs today done means the day is done. Counting the
     * fish tank would leave today impossible to finish, since nothing can check it today.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aCompleteModeDayFinishesWithoutTheItemsThatDoNotRunOnIt() {
        DiaryRoutineResponseDTO created = createWeekendRoutine();

        diaryRoutineService.checkAndUncheckGroup(
                checkOf(created.id(), habitGroupOf(created, meditateId).id()), user.getId());

        transactionTemplate.executeWithoutResult(tx -> {
            User reloaded = userRepository.findById(user.getId()).orElseThrow();
            assertTrue(reloaded.getCompletedDays().contains(today),
                    "meditate was the only item running today, so today is complete");
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theSnapshotRecordsOnlyTheItemsThatRanThatDay() {
        UUID routineId = createWeekendRoutine().id();

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(routineId).orElseThrow();
            User owner = userRepository.findById(user.getId()).orElseThrow();
            RoutineSnapshot snapshot = snapshotService.createSnapshot(routine, owner, today);

            assertEquals(List.of(meditateId),
                    snapshot.getChecks().stream().map(SnapshotCheck::getOriginalItemId).toList());
            assertTrue(!snapshot.getStructureJson().contains(fishTankId.toString()),
                    "the frozen structure leaves the fish tank out too");
            assertTrue(!snapshot.getStructureJson().contains("Chores"),
                    "and the section it emptied");
        });
    }
}
