package beyou.beyouapp.backend.integration.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotStructureSerializer;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotCheckResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotMonthResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SnapshotServiceTest {

    @Mock
    private RoutineSnapshotRepository snapshotRepository;

    @Mock
    private SnapshotCheckRepository snapshotCheckRepository;

    @Mock
    private DiaryRoutineRepository diaryRoutineRepository;

    @Mock
    private SnapshotStructureSerializer structureSerializer;

    @InjectMocks
    private SnapshotService snapshotService;

    private User user;
    private User otherUser;
    private DiaryRoutine routine;
    private UUID routineId;
    private UUID userId;
    private UUID otherUserId;
    private LocalDate snapshotDate;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        routineId = UUID.randomUUID();
        snapshotDate = LocalDate.of(2026, 3, 21);

        user = new User();
        user.setId(userId);
        user.setName("Test User");

        otherUser = new User();
        otherUser.setId(otherUserId);
        otherUser.setName("Other User");

        routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setName("Morning Routine");
        routine.setIconId("icon-morning");
        routine.setUser(user);
        routine.setRoutineSections(new ArrayList<>());
    }

    // ---------------------------------------------------------------
    // createSnapshot tests
    // ---------------------------------------------------------------

    @Test
    void createSnapshot_savesSnapshotWithCorrectFields() {
        String structureJson = "{\"sections\":[]}";
        when(structureSerializer.serializeStructure(routine)).thenReturn(structureJson);
        when(snapshotRepository.save(any(RoutineSnapshot.class))).thenAnswer(invocation -> {
            RoutineSnapshot saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(structureSerializer.createSnapshotChecks(eq(routine), any(RoutineSnapshot.class)))
                .thenReturn(new ArrayList<>());
        when(snapshotCheckRepository.saveAll(anyList())).thenReturn(new ArrayList<>());

        RoutineSnapshot result = snapshotService.createSnapshot(routine, user, snapshotDate);

        assertNotNull(result);
        assertEquals(routine, result.getRoutine());
        assertEquals(user, result.getUser());
        assertEquals(snapshotDate, result.getSnapshotDate());
        assertEquals("Morning Routine", result.getRoutineName());
        assertEquals("icon-morning", result.getRoutineIconId());
        assertEquals(structureJson, result.getStructureJson());
        assertFalse(result.isCompleted());

        verify(snapshotRepository).save(any(RoutineSnapshot.class));
        verify(structureSerializer).serializeStructure(routine);
    }

    @Test
    void createSnapshot_createsAndSavesSnapshotChecks() {
        String structureJson = "{\"sections\":[]}";
        when(structureSerializer.serializeStructure(routine)).thenReturn(structureJson);
        when(snapshotRepository.save(any(RoutineSnapshot.class))).thenAnswer(invocation -> {
            RoutineSnapshot saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        SnapshotCheck habitCheck = buildSnapshotCheck(SnapshotItemType.HABIT, "Meditate", "Morning");
        SnapshotCheck taskCheck = buildSnapshotCheck(SnapshotItemType.TASK, "Review PR", "Morning");
        List<SnapshotCheck> checks = List.of(habitCheck, taskCheck);

        when(structureSerializer.createSnapshotChecks(eq(routine), any(RoutineSnapshot.class)))
                .thenReturn(checks);
        when(snapshotCheckRepository.saveAll(checks)).thenReturn(checks);

        RoutineSnapshot result = snapshotService.createSnapshot(routine, user, snapshotDate);

        assertEquals(2, result.getChecks().size());
        verify(snapshotCheckRepository).saveAll(checks);
        verify(structureSerializer).createSnapshotChecks(eq(routine), any(RoutineSnapshot.class));
    }

    @Test
    void createSnapshot_passesCorrectSnapshotToSerializerForChecks() {
        String structureJson = "{\"sections\":[]}";
        when(structureSerializer.serializeStructure(routine)).thenReturn(structureJson);
        when(snapshotRepository.save(any(RoutineSnapshot.class))).thenAnswer(invocation -> {
            RoutineSnapshot saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(structureSerializer.createSnapshotChecks(eq(routine), any(RoutineSnapshot.class)))
                .thenReturn(new ArrayList<>());
        when(snapshotCheckRepository.saveAll(anyList())).thenReturn(new ArrayList<>());

        snapshotService.createSnapshot(routine, user, snapshotDate);

        ArgumentCaptor<RoutineSnapshot> snapshotCaptor = ArgumentCaptor.forClass(RoutineSnapshot.class);
        verify(structureSerializer).createSnapshotChecks(eq(routine), snapshotCaptor.capture());

        RoutineSnapshot capturedSnapshot = snapshotCaptor.getValue();
        assertEquals(snapshotDate, capturedSnapshot.getSnapshotDate());
        assertEquals("Morning Routine", capturedSnapshot.getRoutineName());
    }

    // ---------------------------------------------------------------
    // getSnapshot tests
    // ---------------------------------------------------------------

    @Test
    void getSnapshot_returnsDTOWhenFoundAndOwned() {
        RoutineSnapshot snapshot = buildSnapshot(user);

        when(snapshotRepository.findByRoutineIdAndSnapshotDate(routineId, snapshotDate))
                .thenReturn(Optional.of(snapshot));

        SnapshotResponseDTO result = snapshotService.getSnapshot(routineId, snapshotDate, userId);

        assertNotNull(result);
        assertEquals(snapshot.getId(), result.id());
        assertEquals(snapshotDate, result.snapshotDate());
        assertEquals("Morning Routine", result.routineName());
        assertEquals("icon-morning", result.routineIconId());
        assertFalse(result.completed());
        assertEquals("{\"sections\":[]}", result.structure());
        assertEquals(1, result.checks().size());

        SnapshotCheckResponseDTO checkDTO = result.checks().get(0);
        assertEquals(SnapshotItemType.HABIT, checkDTO.itemType());
        assertEquals("Meditate", checkDTO.itemName());
    }

    @Test
    void getSnapshot_throwsSnapshotNotFoundWhenMissing() {
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(routineId, snapshotDate))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> snapshotService.getSnapshot(routineId, snapshotDate, userId));

        assertEquals(ErrorKey.SNAPSHOT_NOT_FOUND, exception.getErrorKey());
    }

    @Test
    void getSnapshot_throwsSnapshotNotOwnedWhenWrongUser() {
        RoutineSnapshot snapshot = buildSnapshot(otherUser);

        when(snapshotRepository.findByRoutineIdAndSnapshotDate(routineId, snapshotDate))
                .thenReturn(Optional.of(snapshot));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> snapshotService.getSnapshot(routineId, snapshotDate, userId));

        assertEquals(ErrorKey.SNAPSHOT_NOT_OWNED, exception.getErrorKey());
    }

    // ---------------------------------------------------------------
    // getSnapshotDatesForMonth tests
    // ---------------------------------------------------------------

    @Test
    void getSnapshotDatesForMonth_returnsCorrectDates() {
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));

        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 3, 21)
        );
        when(snapshotRepository.findSnapshotDatesByRoutineIdAndMonth(
                eq(routineId),
                eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 3, 31))
        )).thenReturn(dates);

        SnapshotMonthResponseDTO result = snapshotService.getSnapshotDatesForMonth(
                routineId, "2026-03", userId);

        assertNotNull(result);
        assertEquals(3, result.dates().size());
        assertEquals(LocalDate.of(2026, 3, 1), result.dates().get(0));
        assertEquals(LocalDate.of(2026, 3, 15), result.dates().get(1));
        assertEquals(LocalDate.of(2026, 3, 21), result.dates().get(2));
    }

    @Test
    void getSnapshotDatesForMonth_returnsEmptyListWhenNoDates() {
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));

        when(snapshotRepository.findSnapshotDatesByRoutineIdAndMonth(
                eq(routineId), any(LocalDate.class), any(LocalDate.class)
        )).thenReturn(List.of());

        SnapshotMonthResponseDTO result = snapshotService.getSnapshotDatesForMonth(
                routineId, "2026-03", userId);

        assertNotNull(result);
        assertTrue(result.dates().isEmpty());
    }

    @Test
    void getSnapshotDatesForMonth_throwsRoutineNotFoundWhenMissing() {
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> snapshotService.getSnapshotDatesForMonth(routineId, "2026-03", userId));

        assertEquals(ErrorKey.ROUTINE_NOT_FOUND, exception.getErrorKey());
    }

    @Test
    void getSnapshotDatesForMonth_throwsRoutineNotOwnedWhenWrongUser() {
        routine.setUser(otherUser);
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(routine));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> snapshotService.getSnapshotDatesForMonth(routineId, "2026-03", userId));

        assertEquals(ErrorKey.ROUTINE_NOT_OWNED, exception.getErrorKey());
    }

    // ---------------------------------------------------------------
    // toResponseDTO tests
    // ---------------------------------------------------------------

    @Test
    void toResponseDTO_convertsAllFieldsCorrectly() {
        RoutineSnapshot snapshot = buildSnapshot(user);

        SnapshotResponseDTO result = snapshotService.toResponseDTO(snapshot);

        assertEquals(snapshot.getId(), result.id());
        assertEquals(snapshotDate, result.snapshotDate());
        assertEquals("Morning Routine", result.routineName());
        assertEquals("icon-morning", result.routineIconId());
        assertFalse(result.completed());
        assertEquals("{\"sections\":[]}", result.structure());
        assertEquals(1, result.checks().size());
    }

    @Test
    void toResponseDTO_handlesEmptyChecks() {
        RoutineSnapshot snapshot = buildSnapshot(user);
        snapshot.setChecks(new ArrayList<>());

        SnapshotResponseDTO result = snapshotService.toResponseDTO(snapshot);

        assertNotNull(result.checks());
        assertTrue(result.checks().isEmpty());
    }

    // ---------------------------------------------------------------
    // toCheckResponseDTO tests
    // ---------------------------------------------------------------

    @Test
    void toCheckResponseDTO_convertsAllFieldsCorrectly() {
        UUID checkId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        LocalTime checkTime = LocalTime.of(7, 30);

        SnapshotCheck check = new SnapshotCheck();
        check.setId(checkId);
        check.setItemType(SnapshotItemType.TASK);
        check.setItemName("Review PR");
        check.setItemIconId("icon-review");
        check.setSectionName("Morning");
        check.setOriginalGroupId(groupId);
        check.setDifficulty(4);
        check.setImportance(5);
        check.setChecked(true);
        check.setSkipped(false);
        check.setCheckTime(checkTime);
        check.setXpGenerated(25.5);

        SnapshotCheckResponseDTO result = snapshotService.toCheckResponseDTO(check);

        assertEquals(checkId, result.id());
        assertEquals(SnapshotItemType.TASK, result.itemType());
        assertEquals("Review PR", result.itemName());
        assertEquals("icon-review", result.itemIconId());
        assertEquals("Morning", result.sectionName());
        assertEquals(groupId, result.originalGroupId());
        assertEquals(4, result.difficulty());
        assertEquals(5, result.importance());
        assertTrue(result.checked());
        assertFalse(result.skipped());
        assertEquals(checkTime, result.checkTime());
        assertEquals(25.5, result.xpGenerated(), 0.001);
    }

    @Test
    void toCheckResponseDTO_handlesNullCheckTime() {
        SnapshotCheck check = buildSnapshotCheck(SnapshotItemType.HABIT, "Meditate", "Morning");
        check.setCheckTime(null);

        SnapshotCheckResponseDTO result = snapshotService.toCheckResponseDTO(check);

        assertNull(result.checkTime());
    }

    // ---------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------

    private RoutineSnapshot buildSnapshot(User owner) {
        RoutineSnapshot snapshot = new RoutineSnapshot();
        snapshot.setId(UUID.randomUUID());
        snapshot.setRoutine(routine);
        snapshot.setUser(owner);
        snapshot.setSnapshotDate(snapshotDate);
        snapshot.setRoutineName("Morning Routine");
        snapshot.setRoutineIconId("icon-morning");
        snapshot.setStructureJson("{\"sections\":[]}");
        snapshot.setCompleted(false);

        SnapshotCheck check = buildSnapshotCheck(SnapshotItemType.HABIT, "Meditate", "Morning");
        check.setSnapshot(snapshot);
        snapshot.setChecks(new ArrayList<>(List.of(check)));

        return snapshot;
    }

    private SnapshotCheck buildSnapshotCheck(SnapshotItemType type, String name, String sectionName) {
        SnapshotCheck check = new SnapshotCheck();
        check.setId(UUID.randomUUID());
        check.setItemType(type);
        check.setItemName(name);
        check.setItemIconId("icon-" + name.toLowerCase().replace(" ", "-"));
        check.setSectionName(sectionName);
        check.setOriginalItemId(UUID.randomUUID());
        check.setOriginalGroupId(UUID.randomUUID());
        check.setDifficulty(3);
        check.setImportance(4);
        check.setChecked(false);
        check.setSkipped(false);
        check.setXpGenerated(0.0);
        return check;
    }
}
