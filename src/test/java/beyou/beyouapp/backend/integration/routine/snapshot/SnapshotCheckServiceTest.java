package beyou.beyouapp.backend.integration.routine.snapshot;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckDayRecorder;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUserDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckService;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayCalculator;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SnapshotCheckServiceTest {

    @Mock private RoutineSnapshotRepository snapshotRepository;
    @Mock private SnapshotCheckRepository snapshotCheckRepository;
    @Mock private DiaryRoutineRepository diaryRoutineRepository;
    @Mock private HabitRepository habitRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private XpCalculatorService xpCalculatorService;
    @Mock private XpDecayCalculator xpDecayCalculator;
    @Mock private RefreshUiDtoBuilder refreshUiDtoBuilder;
    @Mock private AuthenticatedUser authenticatedUser;
    @Mock private EntityCheckDayRepository entityCheckDayRepository;
    @Mock private UserCacheEvictService userCacheEvictService;

    /**
     * Built by hand rather than with {@code @InjectMocks} so the recorder is the real one.
     * The streak scenarios below are assertions about arithmetic over stored days, and a
     * mocked recorder would only prove that the service called something.
     */
    private SnapshotCheckService snapshotCheckService;

    /** Every snapshot the scheduler writes is for a day already over. */
    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 3, 20);

    /** Stand-in for the habit's stored history, mutated by the recorder under test. */
    private final List<EntityCheckDay> habitHistory = new ArrayList<>();

    private User user;
    private User otherUser;
    private DiaryRoutine routine;
    private RoutineSnapshot snapshot;
    private SnapshotCheck habitCheck;
    private SnapshotCheck taskCheck;
    private UUID snapshotId;
    private UUID habitCheckId;
    private UUID taskCheckId;
    private UUID routineId;
    private RefreshUiDTO dummyRefreshUiDTO;

    @BeforeEach
    void setUp() {
        snapshotCheckService = new SnapshotCheckService(
                snapshotRepository, snapshotCheckRepository, diaryRoutineRepository,
                habitRepository, taskRepository, userRepository, userService,
                xpCalculatorService, xpDecayCalculator, refreshUiDtoBuilder, authenticatedUser,
                new CheckDayRecorder(entityCheckDayRepository), userCacheEvictService);
        habitHistory.clear();

        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        routineId = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        habitCheckId = UUID.randomUUID();
        taskCheckId = UUID.randomUUID();

        user = new User();
        user.setId(userId);
        user.setName("Test User");
        user.setTimezone("UTC");
        user.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        user.setConstanceConfiguration(ConstanceConfiguration.ANY);
        user.setXpProgress(new XpProgress(100.0, 5, 80.0, 150.0));
        user.setCompletedDays(new HashSet<>());
        user.setMaxConstance(0);

        otherUser = new User();
        otherUser.setId(otherUserId);
        otherUser.setName("Other User");

        routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setName("Morning Routine");
        routine.setUser(user);
        routine.setXpProgress(new XpProgress(50.0, 3, 30.0, 80.0));
        routine.setRoutineSections(new ArrayList<>());

        snapshot = new RoutineSnapshot();
        snapshot.setId(snapshotId);
        snapshot.setRoutine(routine);
        snapshot.setUser(user);
        snapshot.setSnapshotDate(SNAPSHOT_DATE);
        snapshot.setRoutineName("Morning Routine");
        snapshot.setCompleted(false);

        habitCheck = buildSnapshotCheck(habitCheckId, SnapshotItemType.HABIT, "Meditate", "Morning", 3, 4);
        habitCheck.setSnapshot(snapshot);

        taskCheck = buildSnapshotCheck(taskCheckId, SnapshotItemType.TASK, "Review PR", "Morning", 2, 5);
        taskCheck.setSnapshot(snapshot);

        snapshot.setChecks(new ArrayList<>(List.of(habitCheck, taskCheck)));

        RefreshUserDTO refreshUserDTO = new RefreshUserDTO(0, false, 0, 100.0, 5, 80.0, 150.0);
        dummyRefreshUiDTO = new RefreshUiDTO(refreshUserDTO, null, null, null);
    }

    // ---------------------------------------------------------------
    // checkOrUncheckSnapshotItem — check an unchecked item
    // ---------------------------------------------------------------

    @Test
    void checkUncheckedHabitItem_appliesDecayedXpAndSetsChecked() {
        Habit habit = new Habit();
        habit.setId(habitCheck.getOriginalItemId());
        List<Category> categories = List.of(buildCategory());
        habit.setCategories(categories);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(habitRepository.findById(habitCheck.getOriginalItemId())).thenReturn(Optional.of(habit));
        // new base = 5 * (3 + 4) = 35; decay mocked to a realistic 1-day-late GRADUAL 0.8 -> 28
        when(xpDecayCalculator.calculateDecayedXp(eq(35.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(28.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        RefreshUiDTO result = snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        assertNotNull(result);
        assertTrue(habitCheck.isChecked());
        assertFalse(habitCheck.isSkipped());
        assertNotNull(habitCheck.getCheckTime());
        assertEquals(28.0, habitCheck.getXpGenerated(), 0.001);

        verify(xpCalculatorService).addXpToUserRoutineHabitAndCategoriesAndPersist(
                eq(user), eq(28.0), eq(routine), eq(habit), eq(categories));
        verify(snapshotCheckRepository).save(habitCheck);
        verify(snapshotRepository).save(snapshot);
        verify(userRepository).save(user);
    }

    @Test
    void checkUncheckedTaskItem_appliesDecayedXpViaCategories() {
        Task task = new Task();
        task.setId(taskCheck.getOriginalItemId());
        List<Category> categories = List.of(buildCategory());
        task.setCategories(categories);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(taskCheckId)).thenReturn(Optional.of(taskCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(taskRepository.findById(taskCheck.getOriginalItemId())).thenReturn(Optional.of(task));
        // new base = 5 * (2 + 5) = 35; decay mocked to a realistic 1-day-late GRADUAL 0.8 -> 28
        when(xpDecayCalculator.calculateDecayedXp(eq(35.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(28.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        RefreshUiDTO result = snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, taskCheckId);

        assertNotNull(result);
        assertTrue(taskCheck.isChecked());
        assertEquals(28.0, taskCheck.getXpGenerated(), 0.001);

        verify(xpCalculatorService).addXpToUserRoutineAndCategoriesAndPersist(
                eq(user), eq(28.0), eq(routine), eq(categories));
    }

    // ---------------------------------------------------------------
    // checkOrUncheckSnapshotItem — uncheck a checked item
    // ---------------------------------------------------------------

    @Test
    void uncheckCheckedItem_reversesStoredXpAndClearsCheckState() {
        habitCheck.setChecked(true);
        habitCheck.setCheckTime(LocalTime.of(8, 0));
        habitCheck.setXpGenerated(96.0);

        Habit habit = new Habit();
        habit.setId(habitCheck.getOriginalItemId());
        List<Category> categories = List.of(buildCategory());
        habit.setCategories(categories);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(habitRepository.findById(habitCheck.getOriginalItemId())).thenReturn(Optional.of(habit));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        RefreshUiDTO result = snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        assertNotNull(result);
        assertFalse(habitCheck.isChecked());
        assertNull(habitCheck.getCheckTime());
        assertEquals(0.0, habitCheck.getXpGenerated(), 0.001);

        verify(xpCalculatorService).removeXpOfUserRoutineHabitAndCategoriesAndPersist(
                eq(user), eq(96.0), eq(routine), eq(habit), eq(categories));
    }

    // ---------------------------------------------------------------
    // checkOrUncheckSnapshotItem — deleted original item fallback
    // ---------------------------------------------------------------

    @Test
    void checkDeletedOriginalHabitItem_fallsBackToUserAndRoutineOnly() {
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(habitRepository.findById(habitCheck.getOriginalItemId())).thenReturn(Optional.empty());
        when(xpDecayCalculator.calculateDecayedXp(eq(35.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(28.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        verify(xpCalculatorService).addXpToUserAndRoutineOnly(user, 28.0, routine);
        verify(xpCalculatorService, never()).addXpToUserRoutineHabitAndCategoriesAndPersist(
                any(User.class), anyDouble(), any(), any(Habit.class), anyList());
    }

    @Test
    void checkDeletedOriginalTaskItem_fallsBackToUserAndRoutineOnly() {
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(taskCheckId)).thenReturn(Optional.of(taskCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(taskRepository.findById(taskCheck.getOriginalItemId())).thenReturn(Optional.empty());
        when(xpDecayCalculator.calculateDecayedXp(eq(35.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(28.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, taskCheckId);

        verify(xpCalculatorService).addXpToUserAndRoutineOnly(user, 28.0, routine);
        verify(xpCalculatorService, never()).addXpToUserRoutineAndCategoriesAndPersist(
                any(User.class), anyDouble(), any(), anyList());
    }

    @Test
    void uncheckDeletedOriginalItem_fallsBackToRemoveFromUserAndRoutineOnly() {
        habitCheck.setChecked(true);
        habitCheck.setCheckTime(LocalTime.of(8, 0));
        habitCheck.setXpGenerated(96.0);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(habitRepository.findById(habitCheck.getOriginalItemId())).thenReturn(Optional.empty());
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        verify(xpCalculatorService).removeXpFromUserAndRoutineOnly(user, 96.0, routine);
    }

    // ---------------------------------------------------------------
    // checkOrUncheckSnapshotItem — ownership validation
    // ---------------------------------------------------------------

    @Test
    void checkOrUncheck_snapshotNotOwned_throwsBusinessException() {
        snapshot.setUser(otherUser);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId));

        assertEquals(ErrorKey.SNAPSHOT_NOT_OWNED, exception.getErrorKey());
        verifyNoInteractions(xpCalculatorService);
    }

    @Test
    void checkOrUncheck_snapshotNotFound_throwsBusinessException() {
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId));

        assertEquals(ErrorKey.SNAPSHOT_NOT_FOUND, exception.getErrorKey());
    }

    @Test
    void checkOrUncheck_snapshotCheckNotFound_throwsBusinessException() {
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId));

        assertEquals(ErrorKey.SNAPSHOT_CHECK_NOT_FOUND, exception.getErrorKey());
    }

    @Test
    void checkOrUncheck_checkNotInSnapshot_throwsBusinessException() {
        RoutineSnapshot otherSnapshot = new RoutineSnapshot();
        otherSnapshot.setId(UUID.randomUUID());
        habitCheck.setSnapshot(otherSnapshot);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId));

        assertEquals(ErrorKey.SNAPSHOT_CHECK_NOT_IN_SNAPSHOT, exception.getErrorKey());
    }

    // ---------------------------------------------------------------
    // skipOrUnskipSnapshotItem — toggle skipped
    // ---------------------------------------------------------------

    @Test
    void skipUncheckedItem_setsSkippedTrue() {
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        assertFalse(habitCheck.isSkipped());

        RefreshUiDTO result = snapshotCheckService.skipOrUnskipSnapshotItem(snapshotId, habitCheckId);

        assertNotNull(result);
        assertTrue(habitCheck.isSkipped());
        verify(snapshotCheckRepository).save(habitCheck);
        verify(snapshotRepository).save(snapshot);
        verifyNoInteractions(xpCalculatorService);
    }

    @Test
    void unskipSkippedItem_setsSkippedFalse() {
        habitCheck.setSkipped(true);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        RefreshUiDTO result = snapshotCheckService.skipOrUnskipSnapshotItem(snapshotId, habitCheckId);

        assertNotNull(result);
        assertFalse(habitCheck.isSkipped());
    }

    @Test
    void skipAlreadyCheckedItem_isNoOp() {
        habitCheck.setChecked(true);
        habitCheck.setCheckTime(LocalTime.of(8, 0));
        habitCheck.setXpGenerated(96.0);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        RefreshUiDTO result = snapshotCheckService.skipOrUnskipSnapshotItem(snapshotId, habitCheckId);

        assertNotNull(result);
        assertTrue(habitCheck.isChecked());
        assertFalse(habitCheck.isSkipped());
        verify(snapshotCheckRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
    }

    // ---------------------------------------------------------------
    // Completed flag recalculation
    // ---------------------------------------------------------------

    @Test
    void completedRecalculation_anyMode_trueWhenAnyChecked() {
        user.setConstanceConfiguration(ConstanceConfiguration.ANY);
        habitCheck.setChecked(false);
        taskCheck.setChecked(false);

        // Set up the task check to become checked
        Task task = new Task();
        task.setId(taskCheck.getOriginalItemId());
        List<Category> categories = List.of(buildCategory());
        task.setCategories(categories);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(taskCheckId)).thenReturn(Optional.of(taskCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(taskRepository.findById(taskCheck.getOriginalItemId())).thenReturn(Optional.of(task));
        when(xpDecayCalculator.calculateDecayedXp(anyDouble(), any(), any(), any())).thenReturn(80.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, taskCheckId);

        assertTrue(snapshot.isCompleted());
    }

    @Test
    void completedRecalculation_anyMode_falseWhenNoneChecked() {
        user.setConstanceConfiguration(ConstanceConfiguration.ANY);
        // Both items unchecked
        habitCheck.setChecked(false);
        taskCheck.setChecked(false);
        snapshot.setCompleted(false);

        // Uncheck the already-unchecked habit (no-op path wouldn't happen, so let's
        // simulate unchecking a previously checked item)
        habitCheck.setChecked(true);
        habitCheck.setXpGenerated(96.0);
        habitCheck.setCheckTime(LocalTime.of(8, 0));

        Habit habit = new Habit();
        habit.setId(habitCheck.getOriginalItemId());
        habit.setCategories(List.of(buildCategory()));

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(habitRepository.findById(habitCheck.getOriginalItemId())).thenReturn(Optional.of(habit));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        // After unchecking habitCheck, both are unchecked, so completed should be false
        assertFalse(snapshot.isCompleted());
    }

    @Test
    void completedRecalculation_completeMode_trueWhenAllCheckedOrSkipped() {
        user.setConstanceConfiguration(ConstanceConfiguration.COMPLETE);
        habitCheck.setChecked(false);
        habitCheck.setSkipped(true);
        taskCheck.setChecked(false);

        // Check the task item
        Task task = new Task();
        task.setId(taskCheck.getOriginalItemId());
        List<Category> categories = List.of(buildCategory());
        task.setCategories(categories);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(taskCheckId)).thenReturn(Optional.of(taskCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(taskRepository.findById(taskCheck.getOriginalItemId())).thenReturn(Optional.of(task));
        when(xpDecayCalculator.calculateDecayedXp(anyDouble(), any(), any(), any())).thenReturn(80.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, taskCheckId);

        // habitCheck is skipped, taskCheck is now checked => all are checked or skipped => COMPLETE
        assertTrue(snapshot.isCompleted());
    }

    @Test
    void completedRecalculation_completeMode_falseWhenNotAllDone() {
        user.setConstanceConfiguration(ConstanceConfiguration.COMPLETE);
        habitCheck.setChecked(false);
        habitCheck.setSkipped(false);
        taskCheck.setChecked(false);

        // Check only the task item, leave habit neither checked nor skipped
        Task task = new Task();
        task.setId(taskCheck.getOriginalItemId());
        List<Category> categories = List.of(buildCategory());
        task.setCategories(categories);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(taskCheckId)).thenReturn(Optional.of(taskCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(taskRepository.findById(taskCheck.getOriginalItemId())).thenReturn(Optional.of(task));
        when(xpDecayCalculator.calculateDecayedXp(anyDouble(), any(), any(), any())).thenReturn(80.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, taskCheckId);

        // taskCheck is checked but habitCheck is neither checked nor skipped => not COMPLETE
        assertFalse(snapshot.isCompleted());
    }

    // ---------------------------------------------------------------
    // skipOrUnskipSnapshotItem — ownership validation
    // ---------------------------------------------------------------

    @Test
    void skipOrUnskip_snapshotNotOwned_throwsBusinessException() {
        snapshot.setUser(otherUser);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> snapshotCheckService.skipOrUnskipSnapshotItem(snapshotId, habitCheckId));

        assertEquals(ErrorKey.SNAPSHOT_NOT_OWNED, exception.getErrorKey());
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    @Test
    void checkItem_routineDeletedSinceSnapshot_xpAppliedToUserOnly() {
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.empty());
        // new base = 5 * (3 + 4) = 35; decay mocked to a realistic 1-day-late GRADUAL 0.8 -> 28
        when(xpDecayCalculator.calculateDecayedXp(eq(35.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(28.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        RefreshUiDTO result = snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        assertNotNull(result);
        assertTrue(habitCheck.isChecked());
        assertEquals(28.0, habitCheck.getXpGenerated(), 0.001);
        // XP applied to user only since routine is deleted
        verify(xpCalculatorService).addXpToUserOnly(user, 28.0);
    }

    @Test
    void uncheckItem_zeroStoredXp_noXpRemoval() {
        habitCheck.setChecked(true);
        habitCheck.setCheckTime(LocalTime.of(8, 0));
        habitCheck.setXpGenerated(0.0);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        assertFalse(habitCheck.isChecked());
        verifyNoInteractions(xpCalculatorService);
    }

    // ---------------------------------------------------------------
    // U4 — back-dated edits write history and recompute the scalars
    // ---------------------------------------------------------------

    @Test
    void checkingAPastSnapshotDayFlipsThatDayFromMissedToDone() {
        Habit habit = habitBehindTheCheck();
        givenHabitHistory(habit, row(habit, SNAPSHOT_DATE, CheckDayOutcome.MISSED));
        stubSnapshotLookups(habit);
        stubDecay();

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        assertEquals(CheckDayOutcome.DONE, outcomeOn(SNAPSHOT_DATE));
        assertEquals(1, habit.getCheckProgress().getTotalCheckIns());
        assertEquals(1, habit.getCheckProgress().getCurrentStreak());
        assertEquals(SNAPSHOT_DATE, habit.getCheckProgress().getFirstCheckInDate());
        assertEquals(SNAPSHOT_DATE, habit.getCheckProgress().getLastCheckInDate());
    }

    @Test
    void repairingOneMissedDayBetweenTwoRunsOfFiveJoinsTheRunsAndRaisesTheRecord() {
        Habit habit = habitBehindTheCheck();
        habit.getCheckProgress().setBestStreak(5);
        givenHabitHistory(habit, twoRunsOfFiveAroundAMissedDay(habit));
        stubSnapshotLookups(habit);
        stubDecay();

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        // Eleven contiguous done days once the hole is filled: D-5 through D+5.
        for (int offset = -5; offset <= 5; offset++) {
            assertEquals(CheckDayOutcome.DONE, outcomeOn(SNAPSHOT_DATE.plusDays(offset)),
                    "day offset " + offset + " should read DONE after the repair");
        }
        assertEquals(11, habit.getCheckProgress().getTotalCheckIns());

        // The walk is anchored on the owner's today, not on the day just written, so the
        // repair is visible end to end: the whole eleven-day run counts.
        assertEquals(11, habit.getCheckProgress().getCurrentStreak());
        // R13 — the record rises with it, from the five it entered with.
        assertEquals(11, habit.getCheckProgress().getBestStreak());
    }

    @Test
    void uncheckingTheRepairedDayReturnsItToMissedAndLeavesTheRecordStanding() {
        Habit habit = habitBehindTheCheck();
        habit.getCheckProgress().setBestStreak(5);
        givenHabitHistory(habit, twoRunsOfFiveAroundAMissedDay(habit));
        stubSnapshotLookups(habit);
        stubDecay();

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);
        assertEquals(11, habit.getCheckProgress().getBestStreak());

        // Same item, same day, undone again.
        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        assertEquals(CheckDayOutcome.MISSED, outcomeOn(SNAPSHOT_DATE));
        assertEquals(10, habit.getCheckProgress().getTotalCheckIns());
        // Walking back from today, the newer run of five survives; the re-broken day stops it.
        assertEquals(5, habit.getCheckProgress().getCurrentStreak());
        // R13 — the record never falls back.
        assertEquals(11, habit.getCheckProgress().getBestStreak());
    }

    @Test
    void uncheckingAPastDayShortensTheStreakToWhatSurvivesTheMiss() {
        Habit habit = habitBehindTheCheck();
        habitCheck.setChecked(true);
        habitCheck.setCheckTime(LocalTime.of(8, 0));
        habitCheck.setXpGenerated(28.0);
        givenHabitHistory(habit,
                row(habit, SNAPSHOT_DATE.minusDays(1), CheckDayOutcome.DONE),
                row(habit, SNAPSHOT_DATE, CheckDayOutcome.DONE));
        stubSnapshotLookups(habit);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        assertEquals(CheckDayOutcome.MISSED, outcomeOn(SNAPSHOT_DATE));
        assertEquals(1, habit.getCheckProgress().getTotalCheckIns());
        assertEquals(0, habit.getCheckProgress().getCurrentStreak());
    }

    @Test
    void skippingAPastSnapshotDayRecordsSkippedSoTheStreakWalksThroughIt() {
        Habit habit = habitBehindTheCheck();
        givenHabitHistory(habit,
                row(habit, SNAPSHOT_DATE.minusDays(1), CheckDayOutcome.DONE),
                row(habit, SNAPSHOT_DATE, CheckDayOutcome.MISSED));
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(habitRepository.findById(habit.getId())).thenReturn(Optional.of(habit));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.skipOrUnskipSnapshotItem(snapshotId, habitCheckId);

        assertEquals(CheckDayOutcome.SKIPPED, outcomeOn(SNAPSHOT_DATE));
        // R12 — a skip is not a failure, so the walk carries on to the day before it.
        assertEquals(1, habit.getCheckProgress().getCurrentStreak());
    }

    @Test
    void unskippingAPastSnapshotDayPutsItBackToMissed() {
        Habit habit = habitBehindTheCheck();
        habitCheck.setSkipped(true);
        givenHabitHistory(habit,
                row(habit, SNAPSHOT_DATE.minusDays(1), CheckDayOutcome.DONE),
                row(habit, SNAPSHOT_DATE, CheckDayOutcome.SKIPPED));
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(habitRepository.findById(habit.getId())).thenReturn(Optional.of(habit));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.skipOrUnskipSnapshotItem(snapshotId, habitCheckId);

        assertEquals(CheckDayOutcome.MISSED, outcomeOn(SNAPSHOT_DATE));
        assertEquals(0, habit.getCheckProgress().getCurrentStreak());
    }

    @Test
    void aCheckWhoseOriginalHabitWasDeletedWritesNoHistoryRow() {
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(habitRepository.findById(habitCheck.getOriginalItemId())).thenReturn(Optional.empty());
        stubDecay();
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        // The XP fallback still runs; the history write has no owner to hang off, so it is
        // skipped rather than writing a row nothing will ever read or delete.
        verify(xpCalculatorService).addXpToUserAndRoutineOnly(user, 28.0, routine);
        verify(entityCheckDayRepository, never()).save(any(EntityCheckDay.class));
    }

    @Test
    void aOneTimeTaskGetsNoHistoryRow() {
        Task task = new Task();
        task.setId(taskCheck.getOriginalItemId());
        task.setCategories(List.of(buildCategory()));
        task.setOneTimeTask(true);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(taskCheckId)).thenReturn(Optional.of(taskCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(taskRepository.findById(taskCheck.getOriginalItemId())).thenReturn(Optional.of(task));
        stubDecay();
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, taskCheckId);

        // R4 — a one-time task never builds a streak, so it gets no row and no scalars.
        verify(entityCheckDayRepository, never()).save(any(EntityCheckDay.class));
        assertEquals(0, task.getCheckProgress().getTotalCheckIns());
    }

    @Test
    void aRecurringTaskGetsItsOwnHistoryRow() {
        Task task = new Task();
        task.setId(taskCheck.getOriginalItemId());
        task.setCategories(List.of(buildCategory()));

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(taskCheckId)).thenReturn(Optional.of(taskCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(taskRepository.findById(taskCheck.getOriginalItemId())).thenReturn(Optional.of(task));
        when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                CheckDayOwnerType.TASK, task.getId())).thenReturn(new ArrayList<>());
        when(entityCheckDayRepository.save(any(EntityCheckDay.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubDecay();
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, taskCheckId);

        ArgumentCaptor<EntityCheckDay> captor = ArgumentCaptor.forClass(EntityCheckDay.class);
        verify(entityCheckDayRepository).save(captor.capture());
        assertEquals(CheckDayOwnerType.TASK, captor.getValue().getOwnerType());
        assertEquals(task.getId(), captor.getValue().getOwnerId());
        assertEquals(SNAPSHOT_DATE, captor.getValue().getDay());
        assertEquals(CheckDayOutcome.DONE, captor.getValue().getOutcome());
        assertEquals(1, task.getCheckProgress().getTotalCheckIns());
    }

    @Test
    void theHistoryRowCarriesTheSnapshotDayAndNotToday() {
        Habit habit = habitBehindTheCheck();
        givenHabitHistory(habit);
        stubSnapshotLookups(habit);
        stubDecay();

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        ArgumentCaptor<EntityCheckDay> captor = ArgumentCaptor.forClass(EntityCheckDay.class);
        verify(entityCheckDayRepository).save(captor.capture());
        assertEquals(SNAPSHOT_DATE, captor.getValue().getDay());
        assertNotEquals(LocalDate.now(), captor.getValue().getDay());
        assertEquals(user, captor.getValue().getUser());
    }

    // ---------------------------------------------------------------
    // U4 — R16 cache eviction and R19 snapshot tables untouched
    // ---------------------------------------------------------------

    @Test
    void aSnapshotCheckEvictsTheActingUsersCaches() {
        Habit habit = habitBehindTheCheck();
        givenHabitHistory(habit);
        stubSnapshotLookups(habit);
        stubDecay();

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        verify(userCacheEvictService).evictAllUserCaches(user.getId());
    }

    @Test
    void aSnapshotSkipEvictsTheActingUsersCaches() {
        Habit habit = habitBehindTheCheck();
        givenHabitHistory(habit);
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(habitRepository.findById(habit.getId())).thenReturn(Optional.of(habit));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.skipOrUnskipSnapshotItem(snapshotId, habitCheckId);

        verify(userCacheEvictService).evictAllUserCaches(user.getId());
    }

    @Test
    void theSnapshotRowKeepsItsShapeAfterAHistoryWrite() {
        Habit habit = habitBehindTheCheck();
        UUID originalItemId = habitCheck.getOriginalItemId();
        UUID originalGroupId = habitCheck.getOriginalGroupId();
        givenHabitHistory(habit, row(habit, SNAPSHOT_DATE, CheckDayOutcome.MISSED));
        stubSnapshotLookups(habit);
        stubDecay();

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        // R19 — the calendar view reads these; the history write must not disturb them.
        assertEquals(SNAPSHOT_DATE, snapshot.getSnapshotDate());
        assertEquals("Morning Routine", snapshot.getRoutineName());
        assertEquals(routine, snapshot.getRoutine());
        assertEquals(2, snapshot.getChecks().size());
        assertEquals(SnapshotItemType.HABIT, habitCheck.getItemType());
        assertEquals("Meditate", habitCheck.getItemName());
        assertEquals("Morning", habitCheck.getSectionName());
        assertEquals(3, habitCheck.getDifficulty());
        assertEquals(4, habitCheck.getImportance());
        assertEquals(originalItemId, habitCheck.getOriginalItemId());
        assertEquals(originalGroupId, habitCheck.getOriginalGroupId());
        assertTrue(habitCheck.isChecked());
        assertEquals(28.0, habitCheck.getXpGenerated(), 0.001);

        verify(snapshotRepository).save(snapshot);
        verify(snapshotCheckRepository).save(habitCheck);
        verify(snapshotRepository, never()).delete(any());
        verify(snapshotCheckRepository, never()).delete(any());
        verify(snapshotCheckRepository, never()).deleteAll(any());
    }

    // ---------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------

    private Habit habitBehindTheCheck() {
        Habit habit = new Habit();
        habit.setId(habitCheck.getOriginalItemId());
        habit.setCategories(List.of(buildCategory()));
        return habit;
    }

    /** D-5..D-1 done, D missed, D+1..D+5 done. */
    private EntityCheckDay[] twoRunsOfFiveAroundAMissedDay(Habit habit) {
        List<EntityCheckDay> rows = new ArrayList<>();
        for (int back = 5; back >= 1; back--) {
            rows.add(row(habit, SNAPSHOT_DATE.minusDays(back), CheckDayOutcome.DONE));
        }
        rows.add(row(habit, SNAPSHOT_DATE, CheckDayOutcome.MISSED));
        for (int ahead = 1; ahead <= 5; ahead++) {
            rows.add(row(habit, SNAPSHOT_DATE.plusDays(ahead), CheckDayOutcome.DONE));
        }
        return rows.toArray(new EntityCheckDay[0]);
    }

    /**
     * Stands in for the habit's stored history. The recorder reads through
     * {@code findByOwnerTypeAndOwnerId...} and writes through {@code save}, so keeping both
     * over one list makes a second call see what the first one wrote — which is what a
     * repair-then-undo sequence needs.
     */
    private void givenHabitHistory(Habit habit, EntityCheckDay... rows) {
        habitHistory.clear();
        habitHistory.addAll(List.of(rows));
        when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                CheckDayOwnerType.HABIT, habit.getId())).thenReturn(habitHistory);
        when(entityCheckDayRepository.save(any(EntityCheckDay.class))).thenAnswer(invocation -> {
            EntityCheckDay saved = invocation.getArgument(0);
            if (!habitHistory.contains(saved)) {
                habitHistory.add(saved);
            }
            return saved;
        });
    }

    private EntityCheckDay row(Habit habit, LocalDate day, CheckDayOutcome outcome) {
        return new EntityCheckDay(user, CheckDayOwnerType.HABIT, habit.getId(), day, outcome);
    }

    private CheckDayOutcome outcomeOn(LocalDate day) {
        return habitHistory.stream()
                .filter(stored -> day.equals(stored.getDay()))
                .map(EntityCheckDay::getOutcome)
                .findFirst()
                .orElse(null);
    }

    private void stubSnapshotLookups(Habit habit) {
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));
        when(habitRepository.findById(habit.getId())).thenReturn(Optional.of(habit));
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);
    }

    private void stubDecay() {
        when(xpDecayCalculator.calculateDecayedXp(anyDouble(), any(), any(), any()))
                .thenReturn(28.0);
    }

    private SnapshotCheck buildSnapshotCheck(UUID id, SnapshotItemType type, String name,
                                              String sectionName, int difficulty, int importance) {
        SnapshotCheck check = new SnapshotCheck();
        check.setId(id);
        check.setItemType(type);
        check.setItemName(name);
        check.setItemIconId("icon-" + name.toLowerCase().replace(" ", "-"));
        check.setSectionName(sectionName);
        check.setOriginalItemId(UUID.randomUUID());
        check.setOriginalGroupId(UUID.randomUUID());
        check.setDifficulty(difficulty);
        check.setImportance(importance);
        check.setChecked(false);
        check.setSkipped(false);
        check.setXpGenerated(0.0);
        return check;
    }

    private Category buildCategory() {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Health");
        category.setXpProgress(new XpProgress(20.0, 2, 10.0, 50.0));
        return category;
    }
}
