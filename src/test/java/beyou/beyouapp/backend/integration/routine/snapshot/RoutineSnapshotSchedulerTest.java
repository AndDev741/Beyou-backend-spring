package beyou.beyouapp.backend.integration.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotScheduler;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckMigrator;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoutineSnapshotSchedulerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private DiaryRoutineRepository diaryRoutineRepository;

    @Mock
    private RoutineSnapshotRepository snapshotRepository;

    @Mock
    private SnapshotService snapshotService;

    @Mock
    private SnapshotCheckMigrator checkMigrator;

    @InjectMocks
    private RoutineSnapshotScheduler scheduler;

    private User user;
    private UUID userId;
    private DiaryRoutine routine;
    private UUID routineId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        routineId = UUID.randomUUID();

        user = new User();
        user.setId(userId);
        user.setName("Test User");
        user.setTimezone("UTC");

        routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setName("Morning Routine");
        routine.setIconId("icon-morning");
        routine.setUser(user);
        routine.setRoutineSections(new ArrayList<>());

        // Set self-reference so backfill calls go through the same instance
        // (in production, Spring's @Lazy proxy handles this)
        ReflectionTestUtils.setField(scheduler, "self", scheduler);
    }

    // ---------------------------------------------------------------
    // createSnapshotsForUser tests
    // ---------------------------------------------------------------

    @Test
    void createSnapshotsForUser_createsSnapshotForScheduledDay() {
        // 2026-03-20 is a Friday
        LocalDate friday = LocalDate.of(2026, 3, 20);

        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Friday));
        routine.setSchedule(schedule);

        RoutineSnapshot snapshot = buildSnapshot(friday);

        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(routineId, friday))
                .thenReturn(Optional.empty());
        when(snapshotService.createSnapshot(routine, user, friday)).thenReturn(snapshot);

        scheduler.createSnapshotsForUser(user, friday);

        verify(snapshotService).createSnapshot(routine, user, friday);
        verify(checkMigrator).migrateChecks(routine, snapshot, friday);
    }

    @Test
    void createSnapshotsForUser_skipsWhenSnapshotAlreadyExists() {
        // 2026-03-20 is a Friday
        LocalDate friday = LocalDate.of(2026, 3, 20);

        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Friday));
        routine.setSchedule(schedule);

        RoutineSnapshot existingSnapshot = buildSnapshot(friday);

        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(routineId, friday))
                .thenReturn(Optional.of(existingSnapshot));

        scheduler.createSnapshotsForUser(user, friday);

        verify(snapshotService, never()).createSnapshot(any(), any(), any());
        verify(checkMigrator, never()).migrateChecks(any(), any(), any());
    }

    @Test
    void createSnapshotsForUser_skipsRoutineNotScheduledForDay() {
        // 2026-03-20 is a Friday — schedule routine only for Monday
        LocalDate friday = LocalDate.of(2026, 3, 20);

        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Monday));
        routine.setSchedule(schedule);

        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));

        scheduler.createSnapshotsForUser(user, friday);

        verify(snapshotService, never()).createSnapshot(any(), any(), any());
        verify(checkMigrator, never()).migrateChecks(any(), any(), any());
    }

    @Test
    void createSnapshotsForUser_skipsWhenUserHasNoRoutines() {
        LocalDate friday = LocalDate.of(2026, 3, 20);

        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of());

        scheduler.createSnapshotsForUser(user, friday);

        verify(snapshotService, never()).createSnapshot(any(), any(), any());
        verify(checkMigrator, never()).migrateChecks(any(), any(), any());
        verify(snapshotRepository, never()).findByRoutineIdAndSnapshotDate(any(), any());
    }

    // ---------------------------------------------------------------
    // backfillMissedSnapshots tests
    // ---------------------------------------------------------------
    // The backfill method iterates the last 7 days and calls
    // createSnapshotsForUser(user, date) for each. That method is
    // already tested above. These tests verify the backfill loop
    // calls it with the correct date range.

    @Test
    void backfillMissedSnapshots_callsCreateSnapshotsForEachDayInWindow() {
        when(userRepository.findAll()).thenReturn(List.of(user));

        // Stub createSnapshotsForUser — it's @Transactional and
        // already tested above. We just need to allow the calls.
        // Since it reads from repos internally, stub what it needs.
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Monday, WeekDay.Tuesday, WeekDay.Wednesday,
                WeekDay.Thursday, WeekDay.Friday, WeekDay.Saturday, WeekDay.Sunday));
        routine.setSchedule(schedule);

        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(eq(routineId), any()))
                .thenReturn(Optional.empty());
        when(snapshotService.createSnapshot(any(), any(), any()))
                .thenAnswer(inv -> buildSnapshot(inv.getArgument(2)));

        scheduler.backfillMissedSnapshots();

        // Should be called for each of the 7 days in the backfill window
        verify(snapshotService, times(7)).createSnapshot(eq(routine), eq(user), any());
    }

    @Test
    void backfillMissedSnapshots_skipsExistingSnapshots() {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Monday, WeekDay.Tuesday, WeekDay.Wednesday,
                WeekDay.Thursday, WeekDay.Friday, WeekDay.Saturday, WeekDay.Sunday));
        routine.setSchedule(schedule);

        LocalDate yesterday = LocalDate.now().minusDays(1);

        when(userRepository.findAll()).thenReturn(List.of(user));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));
        // All dates already have snapshots
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(eq(routineId), any()))
                .thenReturn(Optional.of(buildSnapshot(yesterday)));

        scheduler.backfillMissedSnapshots();

        verify(snapshotService, never()).createSnapshot(any(), any(), any());
    }

    @Test
    void backfillMissedSnapshots_handlesUserWithNoRoutines() {
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of());

        scheduler.backfillMissedSnapshots();

        verify(snapshotService, never()).createSnapshot(any(), any(), any());
    }

    @Test
    void backfillMissedSnapshots_isolatesUserFailures() {
        User user2 = new User();
        user2.setId(UUID.randomUUID());
        user2.setTimezone("INVALID/TIMEZONE");

        when(userRepository.findAll()).thenReturn(List.of(user2, user));

        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Monday, WeekDay.Tuesday, WeekDay.Wednesday,
                WeekDay.Thursday, WeekDay.Friday, WeekDay.Saturday, WeekDay.Sunday));
        routine.setSchedule(schedule);

        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(eq(routineId), any()))
                .thenReturn(Optional.empty());
        when(snapshotService.createSnapshot(any(), any(), any()))
                .thenAnswer(inv -> buildSnapshot(inv.getArgument(2)));

        // Should not throw — user2 fails but user succeeds
        scheduler.backfillMissedSnapshots();

        // user's routines still get backfilled despite user2's failure
        verify(snapshotService, atLeastOnce()).createSnapshot(eq(routine), eq(user), any());
    }

    // ---------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------

    private RoutineSnapshot buildSnapshot(LocalDate date) {
        RoutineSnapshot snapshot = new RoutineSnapshot();
        snapshot.setId(UUID.randomUUID());
        snapshot.setRoutine(routine);
        snapshot.setUser(user);
        snapshot.setSnapshotDate(date);
        snapshot.setRoutineName(routine.getName());
        snapshot.setRoutineIconId(routine.getIconId());
        snapshot.setStructureJson("{\"sections\":[]}");
        snapshot.setCompleted(false);
        snapshot.setChecks(new ArrayList<>());
        return snapshot;
    }
}
