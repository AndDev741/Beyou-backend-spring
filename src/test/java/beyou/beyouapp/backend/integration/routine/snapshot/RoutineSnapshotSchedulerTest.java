package beyou.beyouapp.backend.integration.routine.snapshot;

import beyou.beyouapp.backend.domain.checkday.DayCloseService;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotScheduler;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckMigrator;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.monitoring.SnapshotJobHeartbeat;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock
    private SnapshotJobHeartbeat heartbeat;

    @Mock
    private DayCloseService dayCloseService;

    @Mock
    private UserCacheEvictService userCacheEvictService;

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
    // Heartbeat tests
    // ---------------------------------------------------------------
    // The collector's monitor for this job alerts on the ABSENCE of a
    // check-in, so the value of the whole mechanism rests on exactly
    // when the signal is and is not sent.

    @Test
    void processSnapshots_signalsHeartbeatAfterASuccessfulCycle() {
        when(userRepository.findDistinctTimezones()).thenReturn(List.of("UTC"));
        // Whether this run lands on midnight UTC depends on the wall clock, so tolerate
        // both branches: either way the cycle must reach its end and check in.
        lenient().when(userRepository.findAllByTimezone(anyString())).thenReturn(List.of());

        scheduler.processSnapshots();

        verify(heartbeat).signalCycleCompleted();
    }

    @Test
    void processSnapshots_doesNotSignalHeartbeatWhenTheRunThrows() {
        // A signal from a run that then blew up would make the monitor a permanent
        // green light — the exact failure the heartbeat exists to catch.
        when(userRepository.findDistinctTimezones())
                .thenThrow(new RuntimeException("database unreachable"));

        assertThatThrownBy(() -> scheduler.processSnapshots())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database unreachable");

        verify(heartbeat, never()).signalCycleCompleted();
    }

    @Test
    void processSnapshots_survivesAFailingHeartbeatSignal() {
        // Monitoring must never become a cause of the outage it is watching.
        when(userRepository.findDistinctTimezones()).thenReturn(List.of());
        doThrow(new RuntimeException("collector unreachable"))
                .when(heartbeat).signalCycleCompleted();

        assertThatCode(() -> scheduler.processSnapshots()).doesNotThrowAnyException();

        verify(heartbeat).signalCycleCompleted();
    }

    @Test
    void backfillMissedSnapshots_doesNotSignalHeartbeat() {
        // Startup backfill is not the scheduled cycle. If it checked in, a backend
        // stuck in a crash-restart loop would keep the monitor green forever.
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of());

        scheduler.backfillMissedSnapshots();

        verify(heartbeat, never()).signalCycleCompleted();
    }

    // ---------------------------------------------------------------
    // Day-close branch (U5)
    // ---------------------------------------------------------------
    // The branch is gated on the local hour, and processSnapshots reads the
    // wall clock. Rather than freeze time, these tests pick a real timezone
    // whose local hour is the one under test — there is always one, because
    // UTC offsets span more than 24 hours.

    @Test
    void processSnapshots_closesThePreviousDayAtTheGraceHour() {
        String timezone = zoneWhereLocalHourIs(DAY_CLOSE_GRACE_HOUR);
        LocalDate closingDay = ZonedDateTime.now(ZoneId.of(timezone)).toLocalDate().minusDays(1);

        when(userRepository.findDistinctTimezones()).thenReturn(List.of(timezone));
        when(userRepository.findAllByTimezone(timezone)).thenReturn(List.of(user));
        when(dayCloseService.closeDay(user, closingDay)).thenReturn(3);

        scheduler.processSnapshots();

        verify(dayCloseService).closeDay(user, closingDay);
        verify(snapshotService, never()).createSnapshot(any(), any(), any());
    }

    @Test
    void processSnapshots_doesNotCloseTheDayOutsideTheGraceHour() {
        // An hour that is neither midnight (snapshots) nor the grace hour.
        String timezone = zoneWhereLocalHourIs(DAY_CLOSE_GRACE_HOUR + 3);

        when(userRepository.findDistinctTimezones()).thenReturn(List.of(timezone));

        scheduler.processSnapshots();

        verifyNoInteractions(dayCloseService);
        verify(userRepository, never()).findAllByTimezone(anyString());
    }

    @Test
    void processSnapshots_clearsTheSharedRoutineCacheOnceForTheWholeBatch() {
        // The `routine` cache is keyed userId_routineId, so it can only be cleared
        // wholesale. Clearing it per user would flush it once for every account in
        // the timezone — the exact waste the UserCacheEvictService split exists to stop.
        String timezone = zoneWhereLocalHourIs(DAY_CLOSE_GRACE_HOUR);
        LocalDate closingDay = ZonedDateTime.now(ZoneId.of(timezone)).toLocalDate().minusDays(1);
        List<User> crowd = List.of(userWithId(), userWithId(), userWithId());

        when(userRepository.findDistinctTimezones()).thenReturn(List.of(timezone));
        when(userRepository.findAllByTimezone(timezone)).thenReturn(crowd);
        when(dayCloseService.closeDay(any(), eq(closingDay))).thenReturn(4);

        scheduler.processSnapshots();

        verify(dayCloseService, times(3)).closeDay(any(), eq(closingDay));
        verify(userCacheEvictService).clearSharedRoutineCache();
        verify(userCacheEvictService, never()).evictAllUserCaches(any());
    }

    @Test
    void processSnapshots_stillSignalsTheHeartbeatWhenOneUsersDayCloseBlowsUp() {
        // One bad account must not read as "the snapshot job is dead".
        String timezone = zoneWhereLocalHourIs(DAY_CLOSE_GRACE_HOUR);
        LocalDate closingDay = ZonedDateTime.now(ZoneId.of(timezone)).toLocalDate().minusDays(1);
        User doomed = userWithId();
        User healthy = userWithId();

        when(userRepository.findDistinctTimezones()).thenReturn(List.of(timezone));
        when(userRepository.findAllByTimezone(timezone)).thenReturn(List.of(doomed, healthy));
        when(dayCloseService.closeDay(doomed, closingDay))
                .thenThrow(new RuntimeException("constraint violation"));
        when(dayCloseService.closeDay(healthy, closingDay)).thenReturn(2);

        assertThatCode(() -> scheduler.processSnapshots()).doesNotThrowAnyException();

        verify(dayCloseService).closeDay(healthy, closingDay);
        verify(heartbeat).signalCycleCompleted();
    }

    @Test
    void backfillMissedSnapshots_neverClosesDays() {
        // The backfill walks 7 days on every boot. Closing them would stamp MISSED on
        // days an entity did not exist for — downtime read back as failure (KTD19).
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of());

        scheduler.backfillMissedSnapshots();

        verifyNoInteractions(dayCloseService);
        verifyNoInteractions(userCacheEvictService);
    }

    // ---------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------

    /** Mirrors RoutineSnapshotScheduler.DAY_CLOSE_GRACE_HOUR, which is private. */
    private static final int DAY_CLOSE_GRACE_HOUR = 2;

    /**
     * A real zone id whose local time is currently at {@code hour}. Offsets run from -12 to
     * +14, so every hour of the day is somebody's right now.
     */
    private static String zoneWhereLocalHourIs(int hour) {
        int wanted = Math.floorMod(hour, 24);
        return ZoneId.getAvailableZoneIds().stream()
                .filter(id -> ZonedDateTime.now(ZoneId.of(id)).getHour() == wanted)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No timezone is currently at hour " + wanted));
    }

    private User userWithId() {
        User other = new User();
        other.setId(UUID.randomUUID());
        other.setTimezone("UTC");
        return other;
    }

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
