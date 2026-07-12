package beyou.beyouapp.backend.integration.routine;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted single-item routine edits used by the AI agent tools
 * (addTaskToRoutineSection / addHabitToRoutineSection / removeRoutineItem).
 */
class RoutineGranularEditTest extends AbstractIntegrationTest {

    @Autowired private DiaryRoutineService diaryRoutineService;
    @Autowired private CategoryService categoryService;
    @Autowired private HabitService habitService;
    @Autowired private TaskService taskService;
    @Autowired private UserRepository userRepository;
    @Autowired private XpByLevelRepository xpByLevelRepository;

    private User user;
    private UUID habitId;
    private UUID taskId;
    private UUID routineId;
    private UUID sectionId;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Granular Edit IT User");
        user.setEmail("granular-edit-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
        if (xpByLevelRepository.findByLevel(0) == null) xpByLevelRepository.save(new XpByLevel(0, 0));
        if (xpByLevelRepository.findByLevel(1) == null) xpByLevelRepository.save(new XpByLevel(1, 100));

        Category category = categoryService.createCategoryEntity(
                new CategoryRequestDTO("Health", "ic", null, ExperienceLevel.BEGINNER), user);
        habitId = habitService.createHabitEntity(
                new CreateHabitDTO("Read", null, null, "ic", 3, 3, List.of(category.getId()),
                        ExperienceLevel.BEGINNER),
                user.getId()).getId();
        taskId = taskService.createTaskEntity(
                new CreateTaskRequestDTO("Estudar", null, "ic", 3, 3, List.of(), false),
                user.getId()).getId();

        DiaryRoutineResponseDTO created = diaryRoutineService.createDiaryRoutine(
                new DiaryRoutineRequestDTO("R", "", List.of(new RoutineSectionRequestDTO(
                        null, "Morning", "ic", LocalTime.of(6, 0), LocalTime.of(9, 0),
                        List.of(), List.of(), false))),
                user);
        routineId = created.id();
        sectionId = created.routineSections().get(0).id();
    }

    /**
     * These tests commit real rows (NOT_SUPPORTED propagation) — leftovers
     * survive the class, and AuthenticationControllerTest's
     * userRepository.deleteAll() then dies on the tasks→users FK (User has no
     * cascade mapping to Task). FK-safe teardown order: routine → task →
     * habit → user (habit delete requires it out of any routine first).
     */
    @AfterEach
    void tearDown() {
        diaryRoutineService.deleteDiaryRoutine(routineId, user.getId());
        taskService.deleteTask(taskId, user.getId());
        habitService.deleteHabit(habitId, user.getId());
        userRepository.delete(user);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void addsTaskAndHabitThenRemovesItemByGroupId() {
        DiaryRoutineResponseDTO withTask = diaryRoutineService.addTaskToSection(
                routineId, sectionId, taskId, LocalTime.of(6, 0), LocalTime.of(6, 30), user.getId());
        assertEquals(1, withTask.routineSections().get(0).taskGroup().size());
        assertEquals(taskId, withTask.routineSections().get(0).taskGroup().get(0).taskId());

        DiaryRoutineResponseDTO withHabit = diaryRoutineService.addHabitToSection(
                routineId, sectionId, habitId, LocalTime.of(7, 0), LocalTime.of(7, 30), user.getId());
        assertEquals(1, withHabit.routineSections().get(0).habitGroup().size());

        UUID taskGroupId = withHabit.routineSections().get(0).taskGroup().get(0).id();
        DiaryRoutineResponseDTO afterRemove = diaryRoutineService.removeItemFromRoutine(
                routineId, taskGroupId, user.getId());
        assertTrue(afterRemove.routineSections().get(0).taskGroup().isEmpty(),
                "removed group is gone");
        assertEquals(1, afterRemove.routineSections().get(0).habitGroup().size(),
                "other items untouched — bounded blast radius");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsItemOutsideSectionWindow() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                diaryRoutineService.addTaskToSection(routineId, sectionId, taskId,
                        LocalTime.of(22, 0), LocalTime.of(23, 0), user.getId()));
        assertEquals(ErrorKey.ITEM_START_OUT_OF_SECTION, exception.getErrorKey());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsUnknownSectionAndUnknownGroup() {
        BusinessException noSection = assertThrows(BusinessException.class, () ->
                diaryRoutineService.addTaskToSection(routineId, UUID.randomUUID(), taskId,
                        LocalTime.of(6, 0), LocalTime.of(6, 30), user.getId()));
        assertEquals(ErrorKey.ROUTINE_SECTION_NOT_FOUND, noSection.getErrorKey());

        BusinessException noGroup = assertThrows(BusinessException.class, () ->
                diaryRoutineService.removeItemFromRoutine(routineId, UUID.randomUUID(), user.getId()));
        assertEquals(ErrorKey.ROUTINE_ITEM_NOT_FOUND, noGroup.getErrorKey());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsRoutineOfAnotherUser() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                diaryRoutineService.addTaskToSection(routineId, sectionId, taskId,
                        LocalTime.of(6, 0), LocalTime.of(6, 30), UUID.randomUUID()));
        assertEquals(ErrorKey.ROUTINE_NOT_OWNED, exception.getErrorKey());
    }
}
