package beyou.beyouapp.backend.unit.routine.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.springframework.security.core.context.SecurityContextHolder;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckDayRecorder;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.CheckXpCalculator;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.Routine;
import beyou.beyouapp.backend.domain.routine.checks.CheckItemService;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroupService;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.HabitGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.TaskGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
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
    UserService userService;

    @Mock
    RefreshUiDtoBuilder refreshUiDtoBuilder;

    @Mock
    CheckDayRecorder checkDayRecorder;

    /**
     * R14 — RefreshUiDtoBuilder now counts the user's streak in scheduled days, which
     * needs the account's stored rows. Only used where the real builder is constructed
     * below; with no rows the walk short-circuits, and the habit scalars these tests
     * assert on never go through it.
     */
    @Mock
    EntityCheckDayRepository entityCheckDayRepository;

    @InjectMocks
    private CheckItemService checkItemService;

    User user = new User();

    @BeforeEach
    void giveTheUserAnIdentity() {
        user.setId(UUID.randomUUID());
    }

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
            // These cases assert against LocalDate.now(): pin the owner to the server zone so the
            // owner-timezone resolution can't shift the expected day out from under them.
            user.setTimezone(ZoneId.systemDefault().getId());

            when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> new RefreshUiDTO(null, null, null, invocation.getArgument(3)));
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
            double expectedXp = CheckXpCalculator.calculate(
                    habit.getDificulty(), habit.getImportance(), 0); // streak 0 entering this check
            assertEquals(25.0, expectedXp, "base XP for difficulty 2 + importance 3 with no streak");
            assertEquals(expectedXp, check.getXpGenerated());
            verify(xpCalculatorService).addXpToUserRoutineHabitAndCategoriesAndPersist(user, expectedXp, routine, habit, habit.getCategories());
            verify(checkDayRecorder).record(
                    user, CheckDayOwnerType.HABIT, habit.getId(), habit.getCheckProgress(),
                    today, CheckDayOutcome.DONE);
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
            verify(xpCalculatorService).removeXpOfUserRoutineHabitAndCategoriesAndPersist(user, xpGenerated, routine, habit, habit.getCategories());
            // The row is rewritten, never deleted. This routine carries no schedule, so the
            // day was never expected of the habit — NOT_SCHEDULED, which is neutral.
            verify(checkDayRecorder).record(
                    user, CheckDayOwnerType.HABIT, habit.getId(), habit.getCheckProgress(),
                    date, CheckDayOutcome.NOT_SCHEDULED);
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
            double expectedXp = CheckXpCalculator.calculate(
                    task.getDificulty(), task.getImportance(), 0); // one-time tasks never build a streak
            assertEquals(25.0, expectedXp, "base XP for difficulty 2 + importance 3 with no streak");
            assertEquals(expectedXp, check.getXpGenerated());
            assertEquals(today, task.getMarkedToDelete());
            verify(xpCalculatorService).addXpToUserRoutineAndCategoriesAndPersist(user, expectedXp, routine, task.getCategories());
            // R4/KTD14 — a one-time task is deleted the day after it is checked, so a day
            // row for it would be orphan history nothing ever reads.
            verify(checkDayRecorder, never()).record(any(), any(), any(), any(), any(), any());
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
            verify(xpCalculatorService).removeXpOfUserRoutineAndCategoriesAndPersist(user, xpGenerated, routine, task.getCategories());
            verify(checkDayRecorder, never()).record(any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldWriteADoneRowForARecurringTask() {
            LocalDate today = LocalDate.now();
            Task task = createTask(2, 3, false, List.of(createCategory(0)));
            TaskGroup taskGroup = createTaskGroup(task);
            UUID routineId = taskGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findTaskGroupByDTO(routineId, taskGroup.getId())).thenReturn(taskGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            new TaskGroupRequestDTO(taskGroup.getId(), taskGroup.getStartTime()),
                            null,
                            today));

            assertNull(task.getMarkedToDelete(), "a recurring task is never marked for deletion");
            verify(checkDayRecorder).record(
                    user, CheckDayOwnerType.TASK, task.getId(), task.getCheckProgress(),
                    today, CheckDayOutcome.DONE);
        }
    }

    /**
     * R3/KTD6 — the streak bonus follows the real streak now, not the lifetime tally the
     * dropped {@code constance} column used to hand over. These are the numbers, spelled
     * out: {@code 5 * (difficulty + importance) * (1 + min(streak * 0.01, 0.5))}.
     */
    @Nested
    class StreakBonusTests {

        @BeforeEach
        void setup() {
            user.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
            user.setCompletedDays(new HashSet<>());
            user.setMaxConstance(0);
            user.setTimezone(ZoneId.systemDefault().getId());

            when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> new RefreshUiDTO(null, null, null, invocation.getArgument(3)));
        }

        @Test
        void aHabitWithNoStreakEarnsTheUnmultipliedBase() {
            assertEquals(25.0, xpFromCheckingHabitWithStreak(0));
        }

        @Test
        void aHabitOnATenDayStreakEarnsTenPercentMore() {
            assertEquals(28.0, xpFromCheckingHabitWithStreak(10),
                    "25 * 1.10 = 27.5, rounded to 28");
        }

        @Test
        void aHabitWhoseStreakBrokeEarnsLessThanItDidBefore() {
            double beforeTheBreak = xpFromCheckingHabitWithStreak(30);
            double afterTheBreak = xpFromCheckingHabitWithStreak(0);

            assertEquals(33.0, beforeTheBreak, "25 * 1.30 = 32.5, rounded to 33");
            assertEquals(25.0, afterTheBreak);
            assertTrue(afterTheBreak < beforeTheBreak,
                    "the bonus follows the real streak, so losing it costs XP on the next check");
        }

        @Test
        void aRecurringTaskEarnsAgainstItsOwnStreak() {
            LocalDate today = LocalDate.now();
            Task task = createTask(2, 3, false, List.of(createCategory(0)));
            task.getCheckProgress().setCurrentStreak(10);
            TaskGroup taskGroup = createTaskGroup(task);
            UUID routineId = taskGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findTaskGroupByDTO(routineId, taskGroup.getId())).thenReturn(taskGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            new TaskGroupRequestDTO(taskGroup.getId(), taskGroup.getStartTime()),
                            null,
                            today));

            assertEquals(28.0, taskGroup.getTaskGroupChecks().get(0).getXpGenerated());
        }

        private double xpFromCheckingHabitWithStreak(int currentStreak) {
            LocalDate today = LocalDate.now();
            Habit habit = createHabit(2, 3, 0, currentStreak, List.of(createCategory(0)));
            HabitGroup habitGroup = createHabitGroup(habit);
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            today));

            return habitGroup.getHabitGroupChecks().get(0).getXpGenerated();
        }
    }

    /**
     * The day rows themselves: which outcome each branch stamps, and what the response
     * carries back once the recompute has run.
     */
    @Nested
    class CheckDayRowTests {

        @BeforeEach
        void setup() {
            user.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
            user.setCompletedDays(new HashSet<>());
            user.setMaxConstance(0);
            user.setTimezone(ZoneId.systemDefault().getId());
        }

        @Test
        void unCheckingAScheduledDayThatIsStillOpenClearsItsRow() {
            stubPassThroughRefresh();
            LocalDate date = LocalDate.now();
            Habit habit = createHabit(1, 1, 40, 0, List.of(createCategory(40)));
            HabitGroup habitGroup = createHabitGroup(habit);
            scheduleRoutineFor(habitGroup.getRoutineSection().getRoutine(), date);

            HabitGroupCheck existingCheck = new HabitGroupCheck();
            existingCheck.setCheckDate(date);
            existingCheck.setChecked(true);
            existingCheck.setXpGenerated(40);
            habitGroup.getHabitGroupChecks().add(existingCheck);

            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            date));

            // "Scheduled and left unchecked" is only true once the day ends. Unchecking at
            // 09:00 says nothing about how the day finishes, so the row goes away and the
            // day reads as unknown until the day-close pass decides.
            verify(checkDayRecorder).clearDay(
                    user, CheckDayOwnerType.HABIT, habit.getId(), habit.getCheckProgress(), date);
            verify(checkDayRecorder, never()).record(
                    any(), any(), any(), any(), any(), eq(CheckDayOutcome.MISSED));
        }

        @Test
        void skippingStampsSkipped() {
            stubPassThroughRefresh();
            LocalDate date = LocalDate.now();
            Habit habit = createHabit(2, 3, 0, 4, List.of(createCategory(0)));
            HabitGroup habitGroup = createHabitGroup(habit);
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.skipOrUnskipItemGroup(
                    new SkipGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            date,
                            true));

            verify(checkDayRecorder).record(
                    user, CheckDayOwnerType.HABIT, habit.getId(), habit.getCheckProgress(),
                    date, CheckDayOutcome.SKIPPED);
            verify(xpCalculatorService, never())
                    .addXpToUserRoutineHabitAndCategoriesAndPersist(any(), anyDouble(), any(), any(), any());
        }

        @Test
        void unskippingReturnsTheDayToItsAbsenceOutcome() {
            stubPassThroughRefresh();
            LocalDate date = LocalDate.now();
            Habit habit = createHabit(2, 3, 0, 4, List.of(createCategory(0)));
            HabitGroup habitGroup = createHabitGroup(habit);
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.skipOrUnskipItemGroup(
                    new SkipGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            date,
                            false));

            verify(checkDayRecorder).record(
                    user, CheckDayOwnerType.HABIT, habit.getId(), habit.getCheckProgress(),
                    date, CheckDayOutcome.NOT_SCHEDULED);
        }

        @Test
        void theResponseCarriesTheHabitsRecomputedStreakRecordAndTotal() {
            // R21 — the card the user just tapped repaints from this response alone. The
            // real RefreshUiDtoBuilder runs here; only the recompute is faked, standing in
            // for what CheckDayRecorder writes onto the habit.
            when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> new RefreshUiDtoBuilder(
                            new UserStreakService(entityCheckDayRepository)).buildRefreshUiDto(
                            invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2), invocation.getArgument(3),
                            invocation.getArgument(4)));
            when(checkDayRecorder.record(any(), any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        CheckProgress progress = invocation.getArgument(3);
                        progress.setCurrentStreak(7);
                        progress.setBestStreak(11);
                        progress.setTotalCheckIns(23);
                        return progress;
                    });

            LocalDate today = LocalDate.now();
            Habit habit = createHabit(2, 3, 0, 6, List.of(createCategory(0)));
            HabitGroup habitGroup = createHabitGroup(habit);
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            RefreshUiDTO refresh = checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            today));

            assertEquals(7, refresh.refreshHabit().currentStreak());
            assertEquals(11, refresh.refreshHabit().bestStreak());
            assertEquals(23, refresh.refreshHabit().totalCheckIns());
        }

        @Test
        void aCheckSucceedsWithNoSecurityContextAtAll() {
            // KTD20 — the agent tools reach this path on a boundedElastic thread that never
            // had a SecurityContext. Identity travels on the routine, so nothing here reads
            // the holder; clearing it must change nothing.
            SecurityContextHolder.clearContext();
            stubPassThroughRefresh();

            LocalDate today = LocalDate.now();
            Habit habit = createHabit(2, 3, 0, 0, List.of(createCategory(0)));
            HabitGroup habitGroup = createHabitGroup(habit);
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            RefreshUiDTO refresh = checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            today));

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertSame(habitGroup.getId(), refresh.refreshItemChecked().groupItemId());
            verify(checkDayRecorder).record(
                    user, CheckDayOwnerType.HABIT, habit.getId(), habit.getCheckProgress(),
                    today, CheckDayOutcome.DONE);
        }

        private void stubPassThroughRefresh() {
            when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> new RefreshUiDTO(null, null, null, invocation.getArgument(3)));
        }

        private void scheduleRoutineFor(Routine routine, LocalDate date) {
            Schedule schedule = new Schedule();
            schedule.setDays(Set.of(ScheduledOnDayResolver.weekDayOf(date)));
            routine.setSchedule(schedule);
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
            // Pinned to the server zone for the same reason as CheckTests above.
            user.setTimezone(ZoneId.systemDefault().getId());

            when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> new RefreshUiDTO(null, null, null, invocation.getArgument(3)));        }

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

    /**
     * R15/KTD10: every date decision in the check path must resolve in the owning user's
     * timezone. These cases pick an owner zone whose local date provably differs from the
     * server's right now, so the assertions are deterministic at any hour of any day.
     */
    @Nested
    class OwnerTimezoneTests {

        @BeforeEach
        void setup() {
            user.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
            user.setCompletedDays(new HashSet<>());
            user.setMaxConstance(0);

            when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> new RefreshUiDTO(null, null, null, invocation.getArgument(3)));
        }

        @Test
        void shouldDateHabitCheckInTheOwnersTimezoneNotTheServersDay() {
            ZoneId ownerZone = zoneWhoseTodayDiffersFromServer();
            user.setTimezone(ownerZone.getId());

            Habit habit = createHabit(2, 3, 0, 0, List.of(createCategory(0)));
            HabitGroup habitGroup = createHabitGroup(habit);
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            null));

            HabitGroupCheck check = habitGroup.getHabitGroupChecks().get(0);
            assertEquals(LocalDate.now(ownerZone), check.getCheckDate());
            assertNotEquals(LocalDate.now(), check.getCheckDate());
        }

        @Test
        void shouldDateSkipInTheOwnersTimezoneWhenTheRequestCarriesNoDate() {
            ZoneId ownerZone = zoneWhoseTodayDiffersFromServer();
            user.setTimezone(ownerZone.getId());

            Habit habit = createHabit(2, 3, 0, 0, List.of(createCategory(0)));
            HabitGroup habitGroup = createHabitGroup(habit);
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.skipOrUnskipItemGroup(
                    new SkipGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            null,
                            true));

            HabitGroupCheck check = habitGroup.getHabitGroupChecks().get(0);
            assertTrue(check.getSkipped());
            assertEquals(LocalDate.now(ownerZone), check.getCheckDate());
            assertNotEquals(LocalDate.now(), check.getCheckDate());
        }

        @Test
        void shouldMarkOneTimeTaskForDeletionOnTheOwnersLocalDay() {
            ZoneId ownerZone = zoneWhoseTodayDiffersFromServer();
            user.setTimezone(ownerZone.getId());

            Task task = createTask(2, 3, true, List.of(createCategory(0)));
            TaskGroup taskGroup = createTaskGroup(task);
            UUID routineId = taskGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findTaskGroupByDTO(routineId, taskGroup.getId())).thenReturn(taskGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            new TaskGroupRequestDTO(taskGroup.getId(), taskGroup.getStartTime()),
                            null,
                            null));

            assertEquals(LocalDate.now(ownerZone), taskGroup.getTaskGroupChecks().get(0).getCheckDate());
            assertEquals(LocalDate.now(ownerZone), task.getMarkedToDelete());
            assertNotEquals(LocalDate.now(), task.getMarkedToDelete());
        }

        @Test
        void shouldFallBackToTheServerZoneWhenTheOwnerHasNoTimezone() {
            user.setTimezone(null);

            Habit habit = createHabit(2, 3, 0, 0, List.of(createCategory(0)));
            HabitGroup habitGroup = createHabitGroup(habit);
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            null));

            assertEquals(LocalDate.now(), habitGroup.getHabitGroupChecks().get(0).getCheckDate());
        }

        @Test
        void shouldFallBackToTheServerZoneWhenTheOwnersTimezoneIsGarbage() {
            user.setTimezone("Not/AZone");

            Habit habit = createHabit(2, 3, 0, 0, List.of(createCategory(0)));
            HabitGroup habitGroup = createHabitGroup(habit);
            UUID routineId = habitGroup.getRoutineSection().getRoutine().getId();
            when(itemGroupService.findHabitGroupByDTO(routineId, habitGroup.getId())).thenReturn(habitGroup);

            checkItemService.checkOrUncheckItemGroup(
                    new CheckGroupRequestDTO(
                            routineId,
                            null,
                            new HabitGroupRequestDTO(habitGroup.getId(), habitGroup.getStartTime()),
                            null));

            assertEquals(LocalDate.now(), habitGroup.getHabitGroupChecks().get(0).getCheckDate());
        }
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

    /**
     * UTC+14 and UTC-12 sit 26 hours apart, so their local dates never coincide — at any
     * instant at least one of them is on a different calendar day than the server. Picking
     * whichever differs keeps the owner-timezone assertions deterministic without a Clock seam.
     */
    private static ZoneId zoneWhoseTodayDiffersFromServer() {
        LocalDate serverToday = LocalDate.now();
        for (String zoneId : List.of("Etc/GMT-14", "Etc/GMT+12")) {
            ZoneId zone = ZoneId.of(zoneId);
            if (!LocalDate.now(zone).equals(serverToday)) {
                return zone;
            }
        }
        throw new IllegalStateException("No zone differed from the server's day — impossible by construction");
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

    private Habit createHabit(int difficulty, int importance, double xp, int currentStreak, List<Category> categories) {
        Habit habit = new Habit();
        habit.setId(UUID.randomUUID());
        habit.setDificulty(difficulty);
        habit.setImportance(importance);
        habit.getXpProgress().setLevel(1);
        habit.getXpProgress().setXp(xp);
        habit.getXpProgress().setActualLevelXp(0);
        habit.getXpProgress().setNextLevelXp(1000);
        habit.getCheckProgress().setCurrentStreak(currentStreak);
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
        routine.setUser(user);
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
        routine.setUser(user);
        routine.setRoutineSections(new ArrayList<>(List.of(section)));
        section.setRoutine(routine);
        taskGroup.setRoutineSection(section);
        return taskGroup;
    }
}
