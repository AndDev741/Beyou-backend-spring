package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
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

    @Test
    void backfillMissedSnapshots_createsSnapshotsForMissedDays() {
        // Routine scheduled every weekday. Last snapshot was 3 days ago.
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Monday, WeekDay.Tuesday, WeekDay.Wednesday,
                WeekDay.Thursday, WeekDay.Friday, WeekDay.Saturday, WeekDay.Sunday));
        routine.setSchedule(schedule);

        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate threeDaysAgo = today.minusDays(3);

        when(userRepository.findAll()).thenReturn(List.of(user));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));
        when(snapshotRepository.findLatestSnapshotDateByRoutineId(routineId))
                .thenReturn(Optional.of(threeDaysAgo));
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(eq(routineId), any()))
                .thenReturn(Optional.empty());
        when(snapshotService.createSnapshot(any(), any(), any()))
                .thenAnswer(inv -> buildSnapshot(inv.getArgument(2)));

        scheduler.backfillMissedSnapshots();

        // Should create snapshots for (threeDaysAgo+1) and (threeDaysAgo+2) = yesterday
        // That's 2 days: today-2 and today-1
        verify(snapshotService, times(2)).createSnapshot(eq(routine), eq(user), any());
        verify(snapshotService).createSnapshot(routine, user, today.minusDays(2));
        verify(snapshotService).createSnapshot(routine, user, yesterday);
    }

    @Test
    void backfillMissedSnapshots_skipsAlreadyExistingSnapshots() {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Monday, WeekDay.Tuesday, WeekDay.Wednesday,
                WeekDay.Thursday, WeekDay.Friday, WeekDay.Saturday, WeekDay.Sunday));
        routine.setSchedule(schedule);

        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate twoDaysAgo = today.minusDays(2);

        when(userRepository.findAll()).thenReturn(List.of(user));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));
        when(snapshotRepository.findLatestSnapshotDateByRoutineId(routineId))
                .thenReturn(Optional.of(twoDaysAgo.minusDays(1)));
        // twoDaysAgo already has a snapshot
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(routineId, twoDaysAgo))
                .thenReturn(Optional.of(buildSnapshot(twoDaysAgo)));
        // yesterday does not
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(routineId, yesterday))
                .thenReturn(Optional.empty());
        when(snapshotService.createSnapshot(any(), any(), any()))
                .thenAnswer(inv -> buildSnapshot(inv.getArgument(2)));

        scheduler.backfillMissedSnapshots();

        // Only yesterday should be created
        verify(snapshotService, times(1)).createSnapshot(routine, user, yesterday);
        verify(snapshotService, never()).createSnapshot(routine, user, twoDaysAgo);
    }

    @Test
    void backfillMissedSnapshots_respectsMaxBackfillWindow() {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Monday, WeekDay.Tuesday, WeekDay.Wednesday,
                WeekDay.Thursday, WeekDay.Friday, WeekDay.Saturday, WeekDay.Sunday));
        routine.setSchedule(schedule);

        when(userRepository.findAll()).thenReturn(List.of(user));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));
        // No snapshots at all — would want to backfill everything, but limited to 7 days
        when(snapshotRepository.findLatestSnapshotDateByRoutineId(routineId))
                .thenReturn(Optional.empty());
        when(snapshotRepository.findByRoutineIdAndSnapshotDate(eq(routineId), any()))
                .thenReturn(Optional.empty());
        when(snapshotService.createSnapshot(any(), any(), any()))
                .thenAnswer(inv -> buildSnapshot(inv.getArgument(2)));

        scheduler.backfillMissedSnapshots();

        // Should create exactly 7 snapshots (MAX_BACKFILL_DAYS)
        verify(snapshotService, times(7)).createSnapshot(eq(routine), eq(user), any());
    }

    @Test
    void backfillMissedSnapshots_noBackfillNeededWhenUpToDate() {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setDays(Set.of(WeekDay.Monday, WeekDay.Tuesday, WeekDay.Wednesday,
                WeekDay.Thursday, WeekDay.Friday, WeekDay.Saturday, WeekDay.Sunday));
        routine.setSchedule(schedule);

        LocalDate yesterday = LocalDate.now().minusDays(1);

        when(userRepository.findAll()).thenReturn(List.of(user));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));
        // Last snapshot is yesterday — fully up to date
        when(snapshotRepository.findLatestSnapshotDateByRoutineId(routineId))
                .thenReturn(Optional.of(yesterday));

        scheduler.backfillMissedSnapshots();

        verify(snapshotService, never()).createSnapshot(any(), any(), any());
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
