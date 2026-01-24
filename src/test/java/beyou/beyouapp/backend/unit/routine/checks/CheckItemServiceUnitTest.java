package beyou.beyouapp.backend.unit.routine.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
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
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;

@ExtendWith(MockitoExtension.class)
class CheckItemServiceUnitTest {

    @Mock
    private ItemGroupService itemGroupService;

    @Mock
    private XpCalculatorService xpCalculatorService;

    @Mock
    AuthenticatedUser authenticatedUser;

    @Mock
    UserService userService;

    @InjectMocks
    private CheckItemService checkItemService;

    User user = new User();

    @Nested
    class CheckTests {
        @BeforeEach
        void setup() {
            XpProgress xpProgress = new XpProgress(
                0D,
                0,
                0D,
                50D
            );
            user.setCompletedDays(Set.of(LocalDate.now().minusDays(1))); // Simulating that the user has a constance of 1 day
            user.setXpProgress(xpProgress);
            user.setMaxConstance(2);

            when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
            when(userService.findUserById(user.getId())).thenReturn(user);
        }
        
        @Test
        void shouldCheckHabitGroupAndIncreaseXpAndConstance() {
            LocalDate today = LocalDate.now();
            Category category = createCategory(0);
            Habit habit = createHabit(2, 3, 0, 0, List.of(category));
            HabitGroup habitGroup = createHabitGroup(habit);

            DiaryRoutine routine = (DiaryRoutine) habitGroup.getRoutineSection().getRoutine();
            UUID routineId = routine.getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            RefreshUiDTO refreshUiDTO = checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            today));

            assertSame(habitGroup.getId(), refreshUiDTO.refreshItemChecked().groupItemId());
            assertEquals(1, habitGroup.getHabitGroupChecks().size());
            HabitGroupCheck check = habitGroup.getHabitGroupChecks().get(0);
            assertTrue(check.isChecked());
            assertEquals(today, check.getCheckDate());
            double expectedXp = 10 * habit.getDificulty() * habit.getImportance();
            assertEquals(expectedXp, check.getXpGenerated());
            assertEquals(1, habit.getConstance());
            verify(xpCalculatorService).addXpToUserRoutineHabitAndCategoriesAndPersist(expectedXp, routine, habit, habit.getCategories());
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
            double xpGenerated = existingCheck.getXpGenerated();

            DiaryRoutine routine = (DiaryRoutine) habitGroup.getRoutineSection().getRoutine();
            UUID routineId = routine.getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            RefreshUiDTO refreshUiDTO = checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            date));

            assertSame(habitGroup.getId(), refreshUiDTO.refreshItemChecked().groupItemId());
            assertEquals(1, habitGroup.getHabitGroupChecks().size());
            HabitGroupCheck check = habitGroup.getHabitGroupChecks().get(0);
            assertFalse(check.isChecked());
            assertEquals(0, check.getXpGenerated());
            assertEquals(1, habit.getConstance());
            verify(xpCalculatorService).removeXpOfUserRoutineHabitAndCategoriesAndPersist(xpGenerated, routine, habit, habit.getCategories());
        }

        @Test
        void shouldCheckTaskGroupAndAddXpAndMarkToDelete() {
            LocalDate today = LocalDate.now();
            Category category = createCategory(0);
            Task task = createTask(2, 3, true, List.of(category));
            TaskGroup taskGroup = createTaskGroup(task);

            DiaryRoutine routine = (DiaryRoutine) taskGroup.getRoutineSection().getRoutine();
            UUID routineId = routine.getId();

            when(itemGroupService.findTaskGroupByDTO(routineId, taskGroup.getId())).thenReturn(taskGroup);

            RefreshUiDTO refreshUiDTO = checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            new TaskGroupRequestDTO(taskGroup.getId(), taskGroup.getStartTime()),
                            null,
                            today));

            assertSame(taskGroup.getId(), refreshUiDTO.refreshItemChecked().groupItemId());
            assertEquals(1, taskGroup.getTaskGroupChecks().size());
            TaskGroupCheck check = taskGroup.getTaskGroupChecks().get(0);
            assertTrue(check.isChecked());
            assertEquals(today, check.getCheckDate());
            double expectedXp = 10 * task.getDificulty() * task.getImportance();
            assertEquals(expectedXp, check.getXpGenerated());
            assertEquals(today, task.getMarkedToDelete());
            verify(xpCalculatorService).addXpToUserRoutineAndCategoriesAndPersist(expectedXp, routine, task.getCategories());
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
            double xpGenerated = existingCheck.getXpGenerated();

            DiaryRoutine routine = (DiaryRoutine) taskGroup.getRoutineSection().getRoutine();
            UUID routineId = routine.getId();
            when(itemGroupService.findTaskGroupByDTO(routineId, taskGroup.getId())).thenReturn(taskGroup);

            RefreshUiDTO refreshUiDTO = checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            new TaskGroupRequestDTO(taskGroup.getId(), taskGroup.getStartTime()),
                            null,
                            date));

            assertSame(taskGroup.getId(), refreshUiDTO.refreshItemChecked().groupItemId());
            assertEquals(1, taskGroup.getTaskGroupChecks().size());
            TaskGroupCheck check = taskGroup.getTaskGroupChecks().get(0);
            assertFalse(check.isChecked());
            assertEquals(0, check.getXpGenerated());
            assertNull(task.getMarkedToDelete());
            verify(xpCalculatorService).removeXpOfUserRoutineAndCategoriesAndPersist(xpGenerated, routine, task.getCategories());
        }
    }

    @Nested
    class ConstanceTests {
        @BeforeEach
        void setup() {
            XpProgress xpProgress = new XpProgress(
                0D,
                0,
                0D,
                50D
            );
            user.setCompletedDays(Set.of(LocalDate.now().minusDays(1))); // Simulating that the user has a constance of 1 day
            user.setXpProgress(xpProgress);
            user.setMaxConstance(2);

            when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
            when(userService.findUserById(user.getId())).thenReturn(user);
        }

        @Test
        void shouldIncreaseUserConstanceWhenCheckingAnyTask() {
            //ARRANGE
            user.setConstanceConfiguration(ConstanceConfiguration.ANY);
            LocalDate today = LocalDate.now();
            Category category = createCategory(0);
            Habit habit = createHabit(2, 3, 0, 0, List.of(category));
            HabitGroup habitGroup = createHabitGroup(habit);
            
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            //ACT
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            today));

            //ASSERT
            verify(userService, times(1)).markDayCompleted(user, today);
            verify(userService, times(0)).unmarkDayComplete(user, today);
        }

        @Test
        void shouldIncreaseUserConstanceWhenCheckingAllTasks() {
            //ARRANGE
            user.setConstanceConfiguration(ConstanceConfiguration.COMPLETE);
            LocalDate today = LocalDate.now();
            Category category = createCategory(0);
            Habit habit = createHabit(2, 3, 0, 0, List.of(category));
            HabitGroup habitGroup = createHabitGroup(habit);
            
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            //ACT
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            today));

            //ASSERT
            verify(userService, times(1)).markDayCompleted(user, today);
            verify(userService, times(0)).unmarkDayComplete(user, today);
        }

        //TODO: Write more test cases

    }

    @Nested
    class ExceptionCases {
        @Test
        void shouldThrowWhenNoItemGroupProvided() {
            CheckGroupRequestDTO request = new CheckGroupRequestDTO(UUID.randomUUID(), null, null, LocalDate.now());

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> checkItemService.checkOrUncheckItemGroup(request));

            assertEquals("No Item group found in the request", exception.getMessage());
        }
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
