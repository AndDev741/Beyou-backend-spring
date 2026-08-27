package beyou.beyouapp.backend.integration.routine;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.RoutineType;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineItemRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.HabitGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LIST routine: a flat, ordered list of habits and tasks with no sections and no times.
 *
 * <p>What these tests are really guarding is that the shape is the ONLY thing that differs.
 * Checking, XP, history rows, snapshots and ownership all run through the code that was
 * already serving DAILY routines, so most of what could break here would break there too —
 * which is why the DAILY assertions live in this file alongside the LIST ones rather than
 * being assumed.
 */
class ListRoutineIntegrationTest extends AbstractIntegrationTest {

    @Autowired private DiaryRoutineService diaryRoutineService;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private CategoryService categoryService;
    @Autowired private HabitService habitService;
    @Autowired private TaskService taskService;
    @Autowired private UserRepository userRepository;
    @Autowired private XpByLevelRepository xpByLevelRepository;
    @Autowired private EntityCheckDayRepository entityCheckDayRepository;
    @Autowired private SnapshotService snapshotService;
    @Autowired private TransactionTemplate transactionTemplate;

    private User user;
    private UUID habitId;
    private UUID secondHabitId;
    private UUID taskId;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("List Routine IT User");
        user.setEmail("list-routine-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
        if (xpByLevelRepository.findByLevel(0) == null) xpByLevelRepository.save(new XpByLevel(0, 0));
        if (xpByLevelRepository.findByLevel(1) == null) xpByLevelRepository.save(new XpByLevel(1, 100));

        Category category = categoryService.createCategoryEntity(
                new CategoryRequestDTO("Health", "ic", null, ExperienceLevel.BEGINNER), user);
        habitId = habitService.createHabitEntity(
                new CreateHabitDTO("Meditate", null, null, "ic", 3, 3, List.of(category.getId()),
                        ExperienceLevel.BEGINNER), user.getId()).getId();
        secondHabitId = habitService.createHabitEntity(
                new CreateHabitDTO("Stretch", null, null, "ic", 3, 3, List.of(category.getId()),
                        ExperienceLevel.BEGINNER), user.getId()).getId();
        taskId = taskService.createTaskEntity(
                new CreateTaskRequestDTO("Buy groceries", null, "ic", 3, 3, List.of(), false),
                user.getId()).getId();
    }

    private DiaryRoutineRequestDTO listRequest(String name, List<RoutineItemRequestDTO> items) {
        return new DiaryRoutineRequestDTO(name, "ic", RoutineType.LIST, null, items);
    }

    private DiaryRoutineResponseDTO createList() {
        return diaryRoutineService.createDiaryRoutine(listRequest("Errands", List.of(
                new RoutineItemRequestDTO(null, habitId, null),
                new RoutineItemRequestDTO(null, null, taskId))), user.getId());
    }

    // ── shape ────────────────────────────────────────────────────────────

    /**
     * The headline difference. Before the List type, every routine had to carry at least one
     * section with a start time, and this exact request was rejected with
     * ROUTINE_SECTION_REQUIRED.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void listRoutineNeedsNoSectionsAndNoTimes() {
        DiaryRoutineResponseDTO created = createList();

        assertEquals(RoutineType.LIST, created.type());
        assertEquals(2, created.items().size(), "both entries come back in the flat list");
        assertEquals(DiaryRoutineResponseDTO.RoutineItemType.HABIT, created.items().get(0).type());
        assertEquals(habitId, created.items().get(0).habitId());
        assertEquals(DiaryRoutineResponseDTO.RoutineItemType.TASK, created.items().get(1).type());
        assertEquals(taskId, created.items().get(1).taskId());
        assertEquals(0, created.items().get(0).orderIndex());
        assertEquals(1, created.items().get(1).orderIndex());
    }

    /**
     * The internal single section, and the fact that it carries no times.
     *
     * <p>Asserted because a non-null time here would not fail anything loudly — it would just
     * quietly put a list item inside a window and hand the time-bounds validators something
     * to reject on the next edit.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void listRoutineStoresOneSectionWithNullTimes() {
        UUID routineId = createList().id();

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(routineId).orElseThrow();
            assertTrue(routine.isList());
            assertEquals(1, routine.getRoutineSections().size(), "exactly one section, always");
            var section = routine.getRoutineSections().get(0);
            assertNull(section.getStartTime());
            assertNull(section.getEndTime());
            assertEquals("Errands", section.getName(), "named after the routine, for the snapshot");
            assertEquals(2, routine.listItems().size());
        });
    }

    /** DAILY loses nothing: a routine with no sections is still refused. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void dailyRoutineStillRequiresSections() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                diaryRoutineService.createDiaryRoutine(
                        new DiaryRoutineRequestDTO("D", "ic", RoutineType.DAILY, List.of(), null),
                        user.getId()));
        assertEquals(ErrorKey.ROUTINE_SECTION_REQUIRED, error.getErrorKey());
    }

    /** A routine with no type at all is DAILY, so every pre-List client keeps working. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void absentTypeMeansDaily() {
        DiaryRoutineResponseDTO created = diaryRoutineService.createDiaryRoutine(
                new DiaryRoutineRequestDTO("D", "ic", null,
                        List.of(new RoutineSectionRequestDTO(null, "Morning", "ic",
                                LocalTime.of(6, 0), LocalTime.of(8, 0), List.of(), List.of(), false)),
                        null),
                user.getId());

        assertEquals(RoutineType.DAILY, created.type());
        assertTrue(created.items().isEmpty(), "a daily routine sends no flat items");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void listRoutineRejectsAnEmptyItemList() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                diaryRoutineService.createDiaryRoutine(listRequest("Empty", List.of()), user.getId()));
        assertEquals(ErrorKey.ROUTINE_ITEMS_REQUIRED, error.getErrorKey());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void listItemNamingBothAHabitAndATaskIsRejected() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                diaryRoutineService.createDiaryRoutine(listRequest("Ambiguous", List.of(
                        new RoutineItemRequestDTO(null, habitId, taskId))), user.getId()));
        assertEquals(ErrorKey.ROUTINE_ITEM_AMBIGUOUS, error.getErrorKey());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void listItemNamingNeitherIsRejected() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                diaryRoutineService.createDiaryRoutine(listRequest("Empty entry", List.of(
                        new RoutineItemRequestDTO(null, null, null))), user.getId()));
        assertEquals(ErrorKey.ROUTINE_ITEM_AMBIGUOUS, error.getErrorKey());
    }

    /** Sections sent for a list are refused rather than silently dropped. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void listRoutineRejectsSections() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                diaryRoutineService.createDiaryRoutine(
                        new DiaryRoutineRequestDTO("Mixed", "ic", RoutineType.LIST,
                                List.of(new RoutineSectionRequestDTO(null, "S", "ic",
                                        LocalTime.of(6, 0), null, List.of(), List.of(), false)),
                                List.of(new RoutineItemRequestDTO(null, habitId, null))),
                        user.getId()));
        assertEquals(ErrorKey.ROUTINE_SHAPE_MISMATCH, error.getErrorKey());
    }

    /** And the mirror: items sent for a daily routine. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void dailyRoutineRejectsFlatItems() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                diaryRoutineService.createDiaryRoutine(
                        new DiaryRoutineRequestDTO("D", "ic", RoutineType.DAILY,
                                List.of(new RoutineSectionRequestDTO(null, "S", "ic",
                                        LocalTime.of(6, 0), null, List.of(), List.of(), false)),
                                List.of(new RoutineItemRequestDTO(null, habitId, null))),
                        user.getId()));
        assertEquals(ErrorKey.ROUTINE_SHAPE_MISMATCH, error.getErrorKey());
    }

    // ── editing ──────────────────────────────────────────────────────────

    /**
     * Reordering keeps the rows, and with them the check history hanging off them.
     *
     * <p>The ids matter more than the order does. A merge that dropped and recreated the
     * groups would produce an identical-looking response and erase every check the user had
     * ever recorded, which is the failure this is really watching for.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reorderingKeepsTheRowsAndTheirHistory() {
        DiaryRoutineResponseDTO created = createList();
        UUID routineId = created.id();
        UUID habitGroupId = created.items().get(0).id();
        UUID taskGroupId = created.items().get(1).id();

        diaryRoutineService.checkAndUncheckGroup(new CheckGroupRequestDTO(
                routineId, null, new HabitGroupRequestDTO(habitGroupId, null), LocalDate.now()),
                user.getId());

        // Task first now, habit second.
        DiaryRoutineResponseDTO updated = diaryRoutineService.updateDiaryRoutine(routineId,
                listRequest("Errands", List.of(
                        new RoutineItemRequestDTO(taskGroupId, null, taskId),
                        new RoutineItemRequestDTO(habitGroupId, habitId, null))),
                user.getId());

        assertEquals(taskGroupId, updated.items().get(0).id(), "same row, new position");
        assertEquals(0, updated.items().get(0).orderIndex());
        assertEquals(habitGroupId, updated.items().get(1).id());
        assertEquals(1, updated.items().get(1).orderIndex());
        assertEquals(1, updated.items().get(1).checks().size(), "the check-in survived the reorder");
    }

    /** An entry left out of the update is removed. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void omittingAnItemRemovesIt() {
        DiaryRoutineResponseDTO created = createList();
        UUID habitGroupId = created.items().get(0).id();

        DiaryRoutineResponseDTO updated = diaryRoutineService.updateDiaryRoutine(created.id(),
                listRequest("Errands", List.of(new RoutineItemRequestDTO(habitGroupId, habitId, null))),
                user.getId());

        assertEquals(1, updated.items().size());
        assertEquals(habitGroupId, updated.items().get(0).id());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void addingAnItemAppendsItAtTheEnd() {
        DiaryRoutineResponseDTO created = createList();

        DiaryRoutineResponseDTO updated = diaryRoutineService.updateDiaryRoutine(created.id(),
                listRequest("Errands", List.of(
                        new RoutineItemRequestDTO(created.items().get(0).id(), habitId, null),
                        new RoutineItemRequestDTO(created.items().get(1).id(), null, taskId),
                        new RoutineItemRequestDTO(null, secondHabitId, null))),
                user.getId());

        assertEquals(3, updated.items().size());
        assertEquals(secondHabitId, updated.items().get(2).habitId());
        assertEquals(2, updated.items().get(2).orderIndex());
    }

    /** Renaming the list renames the section its future snapshots will record. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void renamingTheListRenamesItsSection() {
        DiaryRoutineResponseDTO created = createList();

        diaryRoutineService.updateDiaryRoutine(created.id(),
                listRequest("Weekend errands", List.of(
                        new RoutineItemRequestDTO(created.items().get(0).id(), habitId, null))),
                user.getId());

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(created.id()).orElseThrow();
            assertEquals("Weekend errands", routine.getRoutineSections().get(0).getName());
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRoutineCannotChangeItsType() {
        DiaryRoutineResponseDTO created = createList();

        BusinessException error = assertThrows(BusinessException.class, () ->
                diaryRoutineService.updateDiaryRoutine(created.id(),
                        new DiaryRoutineRequestDTO("Errands", "ic", RoutineType.DAILY,
                                List.of(new RoutineSectionRequestDTO(null, "S", "ic",
                                        LocalTime.of(6, 0), null, List.of(), List.of(), false)),
                                null),
                        user.getId()));
        assertEquals(ErrorKey.ROUTINE_SHAPE_MISMATCH, error.getErrorKey());
    }

    // ── everything else behaves exactly as it does for DAILY ─────────────

    /**
     * The point of the whole design: a list item is checked by the untouched check path, and
     * pays out the same XP and the same history row a sectioned item would.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void checkingAListItemAwardsXpAndWritesHistory() {
        DiaryRoutineResponseDTO created = createList();
        UUID habitGroupId = created.items().get(0).id();

        var refresh = diaryRoutineService.checkAndUncheckGroup(new CheckGroupRequestDTO(
                created.id(), null, new HabitGroupRequestDTO(habitGroupId, null), LocalDate.now()),
                user.getId());

        assertNotNull(refresh);
        transactionTemplate.executeWithoutResult(tx -> {
            User reloaded = userRepository.findById(user.getId()).orElseThrow();
            assertTrue(reloaded.getXpProgress().getXp() > 0, "the account gained XP");

            DiaryRoutine routine = diaryRoutineRepository.findById(created.id()).orElseThrow();
            assertTrue(routine.getXpProgress().getXp() > 0, "and so did the routine itself");

            assertTrue(entityCheckDayRepository.findByUserIdAndDay(user.getId(), LocalDate.now()).stream()
                            .anyMatch(row -> row.getOwnerType() == CheckDayOwnerType.HABIT
                                    && habitId.equals(row.getOwnerId())
                                    && row.getOutcome() == CheckDayOutcome.DONE),
                    "the habit's day closed as DONE, same as it would inside a section");
        });
    }

    /** Unchecking takes the XP back, again through the shared path. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void uncheckingAListItemTakesTheXpBack() {
        DiaryRoutineResponseDTO created = createList();
        UUID habitGroupId = created.items().get(0).id();
        CheckGroupRequestDTO check = new CheckGroupRequestDTO(
                created.id(), null, new HabitGroupRequestDTO(habitGroupId, null), LocalDate.now());

        diaryRoutineService.checkAndUncheckGroup(check, user.getId());
        diaryRoutineService.checkAndUncheckGroup(check, user.getId());

        transactionTemplate.executeWithoutResult(tx -> {
            User reloaded = userRepository.findById(user.getId()).orElseThrow();
            assertEquals(0d, reloaded.getXpProgress().getXp(), 0.001);
        });
    }

    /**
     * A list routine snapshots like any other, with null times all the way down.
     *
     * <p>This is the assertion that would have caught a section time defaulted to 00:00
     * instead of left null: the snapshot is immutable history, so a wrong value here is
     * wrong forever.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void listRoutineSnapshotsWithNoTimes() {
        UUID routineId = createList().id();

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(routineId).orElseThrow();
            User owner = userRepository.findById(user.getId()).orElseThrow();
            RoutineSnapshot snapshot = snapshotService.createSnapshot(routine, owner, LocalDate.now());

            assertEquals(2, snapshot.getChecks().size(), "one snapshot check per list item");

            // Parsed, not substring-matched. A `contains("startTime":null)` passes on the
            // items alone while the section quietly carries 00:00, which is exactly the bug
            // this test exists to catch.
            JsonNode root = readJson(snapshot.getStructureJson());
            JsonNode section = root.get("sections").get(0);
            assertEquals(1, root.get("sections").size(), "one section in the frozen structure too");
            assertEquals("Errands", section.get("name").asText());
            assertTrue(section.get("startTime").isNull(), "the section is timeless");
            assertTrue(section.get("endTime").isNull(), "the section is timeless");
            section.get("items").forEach(item -> {
                assertTrue(item.get("startTime").isNull(), "and so is every item in it");
                assertTrue(item.get("endTime").isNull(), "and so is every item in it");
            });
        });
    }

    private JsonNode readJson(String json) {
        return new ObjectMapper().readTree(json);
    }

    /** Ownership is enforced through the same guard the sectioned path uses. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aListItemCannotBorrowAnotherAccountsHabit() {
        User stranger = new User();
        stranger.setName("Stranger");
        stranger.setEmail("stranger-" + UUID.randomUUID() + "@test.com");
        stranger.setPassword("password123");
        stranger = userRepository.saveAndFlush(stranger);

        UUID strangerId = stranger.getId();
        BusinessException error = assertThrows(BusinessException.class, () ->
                diaryRoutineService.createDiaryRoutine(
                        listRequest("Theft", List.of(new RoutineItemRequestDTO(null, habitId, null))),
                        strangerId));
        assertEquals(ErrorKey.HABIT_NOT_OWNED, error.getErrorKey());
    }

    /** Deleting a list routine takes its section, items and checks with it. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingAListRoutineLeavesNothingBehind() {
        DiaryRoutineResponseDTO created = createList();
        diaryRoutineService.checkAndUncheckGroup(new CheckGroupRequestDTO(
                created.id(), null, new HabitGroupRequestDTO(created.items().get(0).id(), null),
                LocalDate.now()), user.getId());

        diaryRoutineService.deleteDiaryRoutine(created.id(), user.getId());

        assertFalse(diaryRoutineRepository.findById(created.id()).isPresent());
    }
}
