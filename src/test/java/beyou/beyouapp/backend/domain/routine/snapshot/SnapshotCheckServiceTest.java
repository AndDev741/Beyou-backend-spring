package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUserDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
    @Mock private XpCalculatorService xpCalculatorService;
    @Mock private XpDecayCalculator xpDecayCalculator;
    @Mock private RefreshUiDtoBuilder refreshUiDtoBuilder;
    @Mock private AuthenticatedUser authenticatedUser;

    @InjectMocks
    private SnapshotCheckService snapshotCheckService;

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
        snapshot.setSnapshotDate(LocalDate.of(2026, 3, 20));
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
        when(xpDecayCalculator.calculateDecayedXp(eq(120.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(96.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        RefreshUiDTO result = snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        assertNotNull(result);
        assertTrue(habitCheck.isChecked());
        assertFalse(habitCheck.isSkipped());
        assertNotNull(habitCheck.getCheckTime());
        assertEquals(96.0, habitCheck.getXpGenerated(), 0.001);

        verify(xpCalculatorService).addXpToUserRoutineHabitAndCategoriesAndPersist(
                eq(user), eq(96.0), eq(routine), eq(habit), eq(categories));
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
        when(xpDecayCalculator.calculateDecayedXp(eq(100.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(80.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        RefreshUiDTO result = snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, taskCheckId);

        assertNotNull(result);
        assertTrue(taskCheck.isChecked());
        assertEquals(80.0, taskCheck.getXpGenerated(), 0.001);

        verify(xpCalculatorService).addXpToUserRoutineAndCategoriesAndPersist(
                eq(user), eq(80.0), eq(routine), eq(categories));
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
        when(xpDecayCalculator.calculateDecayedXp(eq(120.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(96.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        verify(xpCalculatorService).addXpToUserAndRoutineOnly(user, 96.0, routine);
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
        when(xpDecayCalculator.calculateDecayedXp(eq(100.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(80.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, taskCheckId);

        verify(xpCalculatorService).addXpToUserAndRoutineOnly(user, 80.0, routine);
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
    void checkItem_routineDeletedSinceSnapshot_noXpApplied() {
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(snapshotCheckRepository.findById(habitCheckId)).thenReturn(Optional.of(habitCheck));
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.empty());
        when(xpDecayCalculator.calculateDecayedXp(eq(120.0), eq(XpDecayStrategy.GRADUAL), any(), any()))
                .thenReturn(96.0);
        when(refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user)).thenReturn(dummyRefreshUiDTO);

        RefreshUiDTO result = snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, habitCheckId);

        assertNotNull(result);
        assertTrue(habitCheck.isChecked());
        assertEquals(96.0, habitCheck.getXpGenerated(), 0.001);
        // XP not applied because routine is null
        verifyNoInteractions(xpCalculatorService);
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
    // Helper methods
    // ---------------------------------------------------------------

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
