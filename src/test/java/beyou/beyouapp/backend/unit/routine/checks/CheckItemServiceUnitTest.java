package beyou.beyouapp.backend.unit.routine.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.checks.CheckItemService;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroupService;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.HabitGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.TaskGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;

@ExtendWith(MockitoExtension.class)
class CheckItemServiceUnitTest {

    @Mock
    private HabitService habitService;

    @Mock
    private TaskService taskService;

    @Mock
    private ItemGroupService itemGroupService;

    @Mock
    private XpByLevelRepository xpByLevelRepository;

    @InjectMocks
    private CheckItemService checkItemService;

    @Test
    void shouldCheckHabitGroupAndIncreaseXpAndConstance() {
        LocalDate today = LocalDate.now();
        Category category = createCategory(0);
        Habit habit = createHabit(2, 3, 0, 0, List.of(category));
        HabitGroup habitGroup = createHabitGroup(habit);

        when(itemGroupService.findHabitGroupByDTO(habitGroup.getId())).thenReturn(habitGroup);

        DiaryRoutine routine = checkItemService.checkOrUncheckItemGroup(
                new CheckGroupRequestDTO(
                        habitGroup.getRoutineSection().getRoutine().getId(),
                        null,
                        new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                        today));

        assertSame(habitGroup.getRoutineSection().getRoutine(), routine);
        assertEquals(1, habitGroup.getHabitGroupChecks().size());
        HabitGroupCheck check = habitGroup.getHabitGroupChecks().get(0);
        assertTrue(check.isChecked());
        assertEquals(today, check.getCheckDate());
        double expectedXp = 10 * habit.getDificulty() * habit.getImportance();
        assertEquals(expectedXp, check.getXpGenerated());
        assertEquals(expectedXp, habit.getXpProgress().getXp());
        assertEquals(1, habit.getConstance());
        assertEquals(expectedXp, category.getXpProgress().getXp());
        verify(habitService).editEntity(habit);
    }

    @Test
    void shouldUncheckHabitGroupAndRollbackXp() {
        LocalDate date = LocalDate.now();
        Category category = createCategory(40);
        Habit habit = createHabit(1, 1, 40, 2, List.of(category));
        HabitGroup habitGroup = createHabitGroup(habit);
        HabitGroupCheck existingCheck = new HabitGroupCheck();
        existingCheck.setCheckDate(date);
        existingCheck.setChecked(true);
        existingCheck.setXpGenerated(40);
        habitGroup.getHabitGroupChecks().add(existingCheck);

        when(itemGroupService.findHabitGroupByDTO(habitGroup.getId())).thenReturn(habitGroup);

        DiaryRoutine routine = checkItemService.checkOrUncheckItemGroup(
                new CheckGroupRequestDTO(
                        habitGroup.getRoutineSection().getRoutine().getId(),
                        null,
                        new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                        date));

        assertSame(habitGroup.getRoutineSection().getRoutine(), routine);
        assertEquals(1, habitGroup.getHabitGroupChecks().size());
        HabitGroupCheck check = habitGroup.getHabitGroupChecks().get(0);
        assertFalse(check.isChecked());
        assertEquals(0, check.getXpGenerated());
        assertEquals(0, habit.getXpProgress().getXp());
        assertEquals(1, habit.getConstance());
        assertEquals(0, category.getXpProgress().getXp());
        verify(habitService).editEntity(habit);
    }

    @Test
    void shouldCheckTaskGroupAndAddXpAndMarkToDelete() {
        LocalDate today = LocalDate.now();
        Category category = createCategory(0);
        Task task = createTask(2, 3, true, List.of(category));
        TaskGroup taskGroup = createTaskGroup(task);

        when(itemGroupService.findTaskGroupByDTO(taskGroup.getId())).thenReturn(taskGroup);

        DiaryRoutine routine = checkItemService.checkOrUncheckItemGroup(
                new CheckGroupRequestDTO(
                        taskGroup.getRoutineSection().getRoutine().getId(),
                        new TaskGroupRequestDTO(taskGroup.getId(), taskGroup.getStartTime()),
                        null,
                        today));

        assertSame(taskGroup.getRoutineSection().getRoutine(), routine);
        assertEquals(1, taskGroup.getTaskGroupChecks().size());
        TaskGroupCheck check = taskGroup.getTaskGroupChecks().get(0);
        assertTrue(check.isChecked());
        assertEquals(today, check.getCheckDate());
        double expectedXp = 10 * task.getDificulty() * task.getImportance();
        assertEquals(expectedXp, check.getXpGenerated());
        assertEquals(expectedXp, category.getXpProgress().getXp());
        assertEquals(today, task.getMarkedToDelete());
        verify(taskService).editTask(task);
    }

    @Test
    void shouldUncheckTaskGroupAndRollbackXpAndUnmarkDeletion() {
        LocalDate date = LocalDate.now();
        Category category = createCategory(30);
        Task task = createTask(1, 1, true, List.of(category));
        task.setMarkedToDelete(date);
        TaskGroup taskGroup = createTaskGroup(task);
        TaskGroupCheck existingCheck = new TaskGroupCheck();
        existingCheck.setCheckDate(date);
        existingCheck.setChecked(true);
        existingCheck.setXpGenerated(30);
        taskGroup.getTaskGroupChecks().add(existingCheck);

        when(itemGroupService.findTaskGroupByDTO(taskGroup.getId())).thenReturn(taskGroup);

        DiaryRoutine routine = checkItemService.checkOrUncheckItemGroup(
                new CheckGroupRequestDTO(
                        taskGroup.getRoutineSection().getRoutine().getId(),
                        new TaskGroupRequestDTO(taskGroup.getId(), taskGroup.getStartTime()),
                        null,
                        date));

        assertSame(taskGroup.getRoutineSection().getRoutine(), routine);
        assertEquals(1, taskGroup.getTaskGroupChecks().size());
        TaskGroupCheck check = taskGroup.getTaskGroupChecks().get(0);
        assertFalse(check.isChecked());
        assertEquals(0, check.getXpGenerated());
        assertNull(task.getMarkedToDelete());
        assertEquals(0, category.getXpProgress().getXp());
        verify(taskService).editTask(task);
    }

    @Test
    void shouldThrowWhenNoItemGroupProvided() {
        CheckGroupRequestDTO request = new CheckGroupRequestDTO(UUID.randomUUID(), null, null, LocalDate.now());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> checkItemService.checkOrUncheckItemGroup(request));

        assertEquals("No Item group found in the request", exception.getMessage());
    }

    private Category createCategory(double xp) {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Category");
        category.setIconId("icon");
        category.getXpProgress().setLevel(1);
        category.getXpProgress().setXp(xp);
        category.getXpProgress().setActualLevelXp(0);
        category.getXpProgress().setNextLevelXp(1000);
        return category;
    }

    private Habit createHabit(int difficulty, int importance, double xp, int constance, List<Category> categories) {
        Habit habit = new Habit();
        habit.setId(UUID.randomUUID());
        habit.setDificulty(difficulty);
        habit.setImportance(importance);
        habit.getXpProgress().setLevel(1);
        habit.getXpProgress().setXp(xp);
        habit.getXpProgress().setActualLevelXp(0);
        habit.getXpProgress().setNextLevelXp(1000);
        habit.setConstance(constance);
        habit.setCategories(categories);
        return habit;
    }

    private HabitGroup createHabitGroup(Habit habit) {
        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(UUID.randomUUID());
        habitGroup.setHabit(habit);
        habitGroup.setHabitGroupChecks(new ArrayList<>());
        RoutineSection section = new RoutineSection();
        section.setId(UUID.randomUUID());
        section.setHabitGroups(new ArrayList<>(List.of(habitGroup)));
        section.setTaskGroups(new ArrayList<>());
        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(UUID.randomUUID());
        routine.setRoutineSections(new ArrayList<>(List.of(section)));
        section.setRoutine(routine);
        habitGroup.setRoutineSection(section);
        return habitGroup;
    }

    private Task createTask(int difficulty, int importance, boolean oneTimeTask, List<Category> categories) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setDificulty(difficulty);
        task.setImportance(importance);
        task.setOneTimeTask(oneTimeTask);
        task.setCategories(categories);
        return task;
    }

    private TaskGroup createTaskGroup(Task task) {
        TaskGroup taskGroup = new TaskGroup();
        taskGroup.setId(UUID.randomUUID());
        taskGroup.setTask(task);
        taskGroup.setTaskGroupChecks(new ArrayList<>());
        RoutineSection section = new RoutineSection();
        section.setId(UUID.randomUUID());
        section.setTaskGroups(new ArrayList<>(List.of(taskGroup)));
        section.setHabitGroups(new ArrayList<>());
        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(UUID.randomUUID());
        routine.setRoutineSections(new ArrayList<>(List.of(section)));
        section.setRoutine(routine);
        taskGroup.setRoutineSection(section);
        return taskGroup;
    }
}
