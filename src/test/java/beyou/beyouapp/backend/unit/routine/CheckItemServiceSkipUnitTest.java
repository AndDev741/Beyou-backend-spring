package beyou.beyouapp.backend.unit.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckDayRecorder;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.habit.Habit;
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
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.HabitGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.TaskGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;

@ExtendWith(MockitoExtension.class)
class CheckItemServiceSkipUnitTest {

    @Mock
    private ItemGroupService itemGroupService;

    @Mock
    private XpCalculatorService xpCalculatorService;

    @Mock
    private UserService userService;

    @Mock
    private RefreshUiDtoBuilder refreshUiDtoBuilder;

    @Mock
    private CheckDayRecorder checkDayRecorder;

    @Mock
    private EntityCheckDayRepository entityCheckDayRepository;

    private CheckItemService checkItemService;

    @BeforeEach
    void setUp() {
        checkItemService = new CheckItemService(
                itemGroupService,
                xpCalculatorService,
                userService,
                refreshUiDtoBuilder,
                checkDayRecorder,
                entityCheckDayRepository
        );
        // Lenient: the date-bound tests throw before anything is built.
        lenient().when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any(), any()))
                .thenReturn(new RefreshUiDTO(null, null, null, null));
    }

    @Test
    void shouldSkipHabitGroupAndCountForCompleteConstance() {
        UUID routineId = UUID.randomUUID();
        UUID habitGroupId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2024, 1, 1);

        HabitGroup habitGroup = buildHabitGroup(routineId, habitGroupId);
        when(itemGroupService.findHabitGroupByDTO(routineId, habitGroupId)).thenReturn(habitGroup);

        User user = buildUser(ConstanceConfiguration.COMPLETE);
        habitGroup.getRoutineSection().getRoutine().setUser(user);

        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId,
                null,
                new HabitGroupRequestDTO(habitGroupId, null),
                date,
                true
        );

        checkItemService.skipOrUnskipItemGroup(dto);

        assertEquals(1, habitGroup.getHabitGroupChecks().size());
        HabitGroupCheck check = habitGroup.getHabitGroupChecks().get(0);
        assertFalse(check.isChecked());
        assertTrue(Boolean.TRUE.equals(check.getSkipped()));
        verify(userService, times(1)).markDayCompleted(user, date);
    }

    @Test
    void shouldSkipHabitGroupAndNotCountForAnyConstance() {
        UUID routineId = UUID.randomUUID();
        UUID habitGroupId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2024, 1, 1);

        HabitGroup habitGroup = buildHabitGroup(routineId, habitGroupId);
        when(itemGroupService.findHabitGroupByDTO(routineId, habitGroupId)).thenReturn(habitGroup);

        User user = buildUser(ConstanceConfiguration.ANY);
        habitGroup.getRoutineSection().getRoutine().setUser(user);

        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId,
                null,
                new HabitGroupRequestDTO(habitGroupId, null),
                date,
                true
        );

        checkItemService.skipOrUnskipItemGroup(dto);

        assertEquals(1, habitGroup.getHabitGroupChecks().size());
        HabitGroupCheck check = habitGroup.getHabitGroupChecks().get(0);
        assertFalse(check.isChecked());
        assertTrue(Boolean.TRUE.equals(check.getSkipped()));
        verify(userService, never()).markDayCompleted(user, date);
    }

    @Test
    void shouldReturnNoOpWhenSkippingCheckedTaskGroup() {
        UUID routineId = UUID.randomUUID();
        UUID taskGroupId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2024, 1, 1);

        TaskGroup taskGroup = buildTaskGroup(routineId, taskGroupId);
        TaskGroupCheck existingCheck = new TaskGroupCheck();
        existingCheck.setCheckDate(date);
        existingCheck.setChecked(true);
        existingCheck.setSkipped(false);
        taskGroup.getTaskGroupChecks().add(existingCheck);

        when(itemGroupService.findTaskGroupByDTO(routineId, taskGroupId)).thenReturn(taskGroup);

        User user = buildUser(ConstanceConfiguration.COMPLETE);

        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId,
                new TaskGroupRequestDTO(taskGroupId, null),
                null,
                date,
                true
        );

        checkItemService.skipOrUnskipItemGroup(dto);

        assertEquals(1, taskGroup.getTaskGroupChecks().size());
        TaskGroupCheck check = taskGroup.getTaskGroupChecks().get(0);
        assertTrue(check.isChecked());
        assertFalse(Boolean.TRUE.equals(check.getSkipped()));
        verify(userService, never()).markDayCompleted(user, date);
    }

    // ---------------------------------------------------------------
    // The date bound — the skip endpoint writes permanent history at it
    // ---------------------------------------------------------------

    @Test
    void aHabitSkipDatedAfterTheOwnersTodayIsRejectedAndWritesNothing() {
        // A SKIPPED row on a day that has not happened blocks the MISSED the day-close pass
        // would otherwise land, because that pass is insert-only. Repeat over a range and
        // the streak can never break — the unbounded-streak exploit the constance retirement
        // was meant to end, now paying a capped +50% XP bonus forever.
        UUID routineId = UUID.randomUUID();
        UUID habitGroupId = UUID.randomUUID();

        HabitGroup habitGroup = buildHabitGroup(routineId, habitGroupId);
        when(itemGroupService.findHabitGroupByDTO(routineId, habitGroupId)).thenReturn(habitGroup);

        User user = buildUser(ConstanceConfiguration.COMPLETE);
        user.setTimezone("UTC");
        habitGroup.getRoutineSection().getRoutine().setUser(user);

        LocalDate tomorrow = LocalDate.now(ZoneId.of("UTC")).plusDays(1);
        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId, null, new HabitGroupRequestDTO(habitGroupId, null), tomorrow, true);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> checkItemService.skipOrUnskipItemGroup(dto));

        assertEquals(ErrorKey.INVALID_REQUEST, thrown.getErrorKey());
        assertTrue(habitGroup.getHabitGroupChecks().isEmpty());
        verify(checkDayRecorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTaskSkipDatedAfterTheOwnersTodayIsRejectedAndWritesNothing() {
        // Tools.skipRoutineItem reaches the same method, so the bound has to live here and
        // not in a controller.
        UUID routineId = UUID.randomUUID();
        UUID taskGroupId = UUID.randomUUID();

        TaskGroup taskGroup = buildTaskGroup(routineId, taskGroupId);
        when(itemGroupService.findTaskGroupByDTO(routineId, taskGroupId)).thenReturn(taskGroup);

        User user = buildUser(ConstanceConfiguration.COMPLETE);
        user.setTimezone("UTC");
        taskGroup.getRoutineSection().getRoutine().setUser(user);

        LocalDate nextYear = LocalDate.now(ZoneId.of("UTC")).plusYears(1);
        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId, new TaskGroupRequestDTO(taskGroupId, null), null, nextYear, true);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> checkItemService.skipOrUnskipItemGroup(dto));

        assertEquals(ErrorKey.INVALID_REQUEST, thrown.getErrorKey());
        assertTrue(taskGroup.getTaskGroupChecks().isEmpty());
        verify(checkDayRecorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theOwnersOwnTodayIsTheBoundAndNotTheServersDay() {
        // The owner sits a day ahead of the server. Their today is a legitimate skip date
        // even though it is tomorrow where the JVM is running.
        UUID routineId = UUID.randomUUID();
        UUID habitGroupId = UUID.randomUUID();

        HabitGroup habitGroup = buildHabitGroup(routineId, habitGroupId);
        when(itemGroupService.findHabitGroupByDTO(routineId, habitGroupId)).thenReturn(habitGroup);

        ZoneId aheadOfServer = zoneWhoseTodayIsAtLeastTheServers();
        User user = buildUser(ConstanceConfiguration.COMPLETE);
        user.setTimezone(aheadOfServer.getId());
        habitGroup.getRoutineSection().getRoutine().setUser(user);

        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId, null, new HabitGroupRequestDTO(habitGroupId, null),
                LocalDate.now(aheadOfServer), true);

        checkItemService.skipOrUnskipItemGroup(dto);

        assertEquals(1, habitGroup.getHabitGroupChecks().size());
        assertTrue(Boolean.TRUE.equals(habitGroup.getHabitGroupChecks().get(0).getSkipped()));
    }

    /** Kiritimati is UTC+14 — never behind any server zone, so its today is the upper edge. */
    private ZoneId zoneWhoseTodayIsAtLeastTheServers() {
        return ZoneId.of("Pacific/Kiritimati");
    }

    // ---------------------------------------------------------------
    // Unskipping a closed past day must not destroy a stored check-in
    // ---------------------------------------------------------------
    // The live HabitGroupCheck / TaskGroupCheck rows are ABSENT in every case below, which
    // is the state SnapshotCheckMigrator leaves behind: it bulk-deletes the live rows for a
    // date the moment the midnight snapshot copies them. The pre-existing
    // isHabitGroupChecked / isTaskGroupChecked guard reads exactly those rows, so it passes
    // for precisely the days it was meant to protect. entity_check_day is the only surviving
    // record, and it still says DONE.

    @Test
    void unskippingAClosedPastDayLeavesAStoredDoneAlone() {
        UUID routineId = UUID.randomUUID();
        UUID habitGroupId = UUID.randomUUID();

        HabitGroup habitGroup = buildHabitGroup(routineId, habitGroupId);
        when(itemGroupService.findHabitGroupByDTO(routineId, habitGroupId)).thenReturn(habitGroup);

        User user = buildUser(ConstanceConfiguration.ANY);
        user.setTimezone("UTC");
        DiaryRoutine routine = (DiaryRoutine) habitGroup.getRoutineSection().getRoutine();
        routine.setUser(user);

        LocalDate yesterday = LocalDate.now(ZoneId.of("UTC")).minusDays(1);
        // Scheduled that weekday, so the absence outcome the buggy path writes is MISSED —
        // the streak-breaking one, not a harmless NOT_SCHEDULED.
        scheduleRoutineOn(routine, yesterday);
        // The user's constance record holds the day too, so an unguarded unskip would also
        // pull it back out.
        user.getCompletedDays().add(yesterday);

        storedOutcome(CheckDayOwnerType.HABIT, habitGroup.getHabit().getId(), yesterday,
                CheckDayOutcome.DONE);

        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId, null, new HabitGroupRequestDTO(habitGroupId, null), yesterday, false);

        checkItemService.skipOrUnskipItemGroup(dto);

        // Nothing rewrote the day, nothing cleared it, and no live row was resurrected.
        verify(checkDayRecorder, never()).record(any(), any(), any(), any(), any(), any());
        verify(checkDayRecorder, never()).clearDay(any(), any(), any(), any(), any());
        verify(userService, never()).unmarkDayComplete(any(), any());
        assertTrue(habitGroup.getHabitGroupChecks().isEmpty());
    }

    @Test
    void unskippingAClosedPastDayLeavesAStoredDoneAloneForTasksToo() {
        UUID routineId = UUID.randomUUID();
        UUID taskGroupId = UUID.randomUUID();

        TaskGroup taskGroup = buildTaskGroup(routineId, taskGroupId);
        when(itemGroupService.findTaskGroupByDTO(routineId, taskGroupId)).thenReturn(taskGroup);

        User user = buildUser(ConstanceConfiguration.ANY);
        user.setTimezone("UTC");
        DiaryRoutine routine = (DiaryRoutine) taskGroup.getRoutineSection().getRoutine();
        routine.setUser(user);

        LocalDate yesterday = LocalDate.now(ZoneId.of("UTC")).minusDays(1);
        scheduleRoutineOn(routine, yesterday);
        user.getCompletedDays().add(yesterday);

        storedOutcome(CheckDayOwnerType.TASK, taskGroup.getTask().getId(), yesterday,
                CheckDayOutcome.DONE);

        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId, new TaskGroupRequestDTO(taskGroupId, null), null, yesterday, false);

        checkItemService.skipOrUnskipItemGroup(dto);

        verify(checkDayRecorder, never()).record(any(), any(), any(), any(), any(), any());
        verify(checkDayRecorder, never()).clearDay(any(), any(), any(), any(), any());
        verify(userService, never()).unmarkDayComplete(any(), any());
        assertTrue(taskGroup.getTaskGroupChecks().isEmpty());
    }

    @Test
    void unskippingAClosedPastDayThatStoredASkipStillGoesThrough() {
        // The guard protects a real check-in, not the skip the user is trying to undo.
        // Undoing a back-dated skip is the whole point of the endpoint.
        UUID routineId = UUID.randomUUID();
        UUID habitGroupId = UUID.randomUUID();

        HabitGroup habitGroup = buildHabitGroup(routineId, habitGroupId);
        when(itemGroupService.findHabitGroupByDTO(routineId, habitGroupId)).thenReturn(habitGroup);

        User user = buildUser(ConstanceConfiguration.ANY);
        user.setTimezone("UTC");
        DiaryRoutine routine = (DiaryRoutine) habitGroup.getRoutineSection().getRoutine();
        routine.setUser(user);

        LocalDate yesterday = LocalDate.now(ZoneId.of("UTC")).minusDays(1);
        scheduleRoutineOn(routine, yesterday);

        storedOutcome(CheckDayOwnerType.HABIT, habitGroup.getHabit().getId(), yesterday,
                CheckDayOutcome.SKIPPED);

        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId, null, new HabitGroupRequestDTO(habitGroupId, null), yesterday, false);

        checkItemService.skipOrUnskipItemGroup(dto);

        verify(checkDayRecorder).record(any(), eq(CheckDayOwnerType.HABIT), any(), any(),
                eq(yesterday), eq(CheckDayOutcome.MISSED));
        assertEquals(1, habitGroup.getHabitGroupChecks().size());
    }

    @Test
    void unskippingTheOwnersOwnTodayIsUntouchedByTheGuard() {
        // Today is still open: the user is undoing a skip they made minutes ago, and the
        // stored row for an open day carries no DONE to protect. Reading the repository at
        // all here would be a query on the hot path for nothing.
        UUID routineId = UUID.randomUUID();
        UUID habitGroupId = UUID.randomUUID();

        HabitGroup habitGroup = buildHabitGroup(routineId, habitGroupId);
        when(itemGroupService.findHabitGroupByDTO(routineId, habitGroupId)).thenReturn(habitGroup);

        User user = buildUser(ConstanceConfiguration.ANY);
        user.setTimezone("UTC");
        DiaryRoutine routine = (DiaryRoutine) habitGroup.getRoutineSection().getRoutine();
        routine.setUser(user);

        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        scheduleRoutineOn(routine, today);

        SkipGroupRequestDTO dto = new SkipGroupRequestDTO(
                routineId, null, new HabitGroupRequestDTO(habitGroupId, null), today, false);

        checkItemService.skipOrUnskipItemGroup(dto);

        // An open day gets no row at all (absenceOutcome returns null), so this is clearDay.
        verify(checkDayRecorder).clearDay(any(), eq(CheckDayOwnerType.HABIT), any(), any(),
                eq(today));
        verify(entityCheckDayRepository, never())
                .findByOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(any(), any(), any(), any());
        assertEquals(1, habitGroup.getHabitGroupChecks().size());
    }

    /** Gives the routine a schedule that covers {@code date}'s weekday. */
    private void scheduleRoutineOn(DiaryRoutine routine, LocalDate date) {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(ScheduledOnDayResolver.weekDayOf(date)));
        routine.setSchedule(schedule);
    }

    /** The one row {@code entity_check_day} holds for that owner on that day. */
    private void storedOutcome(CheckDayOwnerType ownerType, UUID ownerId, LocalDate day,
                               CheckDayOutcome outcome) {
        when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                ownerType, ownerId, day, day))
                .thenReturn(List.of(new EntityCheckDay(null, ownerType, ownerId, day, outcome)));
    }

    private HabitGroup buildHabitGroup(UUID routineId, UUID habitGroupId) {
        Habit habit = new Habit();
        habit.setId(UUID.randomUUID());
        habit.setCategories(new ArrayList<>());

        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(habitGroupId);
        habitGroup.setHabit(habit);
        habitGroup.setHabitGroupChecks(new ArrayList<>());

        RoutineSection section = new RoutineSection();
        section.setId(UUID.randomUUID());
        section.setHabitGroups(new ArrayList<>(List.of(habitGroup)));
        section.setTaskGroups(new ArrayList<>());

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setRoutineSections(new ArrayList<>(List.of(section)));

        section.setRoutine(routine);
        habitGroup.setRoutineSection(section);

        return habitGroup;
    }

    private TaskGroup buildTaskGroup(UUID routineId, UUID taskGroupId) {
        Task task = new Task();
        task.setId(UUID.randomUUID());

        TaskGroup taskGroup = new TaskGroup();
        taskGroup.setId(taskGroupId);
        taskGroup.setTask(task);
        taskGroup.setTaskGroupChecks(new ArrayList<>());

        RoutineSection section = new RoutineSection();
        section.setId(UUID.randomUUID());
        section.setTaskGroups(new ArrayList<>(List.of(taskGroup)));
        section.setHabitGroups(new ArrayList<>());

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setRoutineSections(new ArrayList<>(List.of(section)));

        section.setRoutine(routine);
        taskGroup.setRoutineSection(section);

        return taskGroup;
    }

    private User buildUser(ConstanceConfiguration configuration) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setConstanceConfiguration(configuration);
        user.setCompletedDays(new HashSet<>());
        return user;
    }
}
