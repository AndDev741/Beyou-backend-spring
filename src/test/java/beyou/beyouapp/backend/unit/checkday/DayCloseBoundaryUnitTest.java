package beyou.beyouapp.backend.unit.checkday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import beyou.beyouapp.backend.domain.checkday.DayCloseService;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotScheduler;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckMigrator;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.monitoring.SnapshotJobHeartbeat;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * Where a user's day begins and ends, pinned against real zones.
 *
 * <p>The scheduler was never itself wrong: it reads the hour in the STORED zone, and every
 * account stored 'UTC' because nothing on the signup paths set anything else. So a Lisbon
 * account had its day turn over an hour late all summer, and a São Paulo one would have had
 * it turn over three hours early, snapshot a day still in progress, and close it as MISSED
 * while the evening was still going.
 *
 * <p>Now that a real zone can be stored, these hold the boundary in place. They pin the
 * arithmetic that decides which calendar day every permanent row in {@code entity_check_day}
 * and {@code entity_xp_day} is filed under, and nothing else in the suite asserts it against
 * a zone that is neither UTC nor a fixed offset.
 *
 * <p>Two zones, for different reasons. {@code Europe/Lisbon} is where Beyou is actually
 * deployed and the only place DST currently bites. {@code America/Sao_Paulo} is here because
 * one hour of offset can pass by luck on a server that happens to sit an hour away; three
 * cannot.
 */
@ExtendWith(MockitoExtension.class)
class DayCloseBoundaryUnitTest {

    /** Mirrors the private constant in RoutineSnapshotScheduler. */
    private static final int DAY_CLOSE_GRACE_HOUR = 2;

    private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Mock private UserRepository userRepository;
    @Mock private DiaryRoutineRepository diaryRoutineRepository;
    @Mock private RoutineSnapshotRepository snapshotRepository;
    @Mock private SnapshotService snapshotService;
    @Mock private SnapshotCheckMigrator checkMigrator;
    @Mock private SnapshotJobHeartbeat heartbeat;
    @Mock private DayCloseService dayCloseService;
    @Mock private UserCacheEvictService userCacheEvictService;

    @InjectMocks private RoutineSnapshotScheduler scheduler;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setName("Ana");
        ReflectionTestUtils.setField(scheduler, "self", scheduler);
    }

    /** Runs one cycle with the wall clock pinned to {@code instant}, for a user in {@code zone}. */
    private void runCycleAt(String instant, ZoneId zone) {
        user.setTimezone(zone.getId());
        ReflectionTestUtils.setField(scheduler, "clock",
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        when(userRepository.findDistinctTimezones()).thenReturn(List.of(zone.getId()));
        scheduler.processSnapshots();
    }

    /** The same, with the by-timezone lookup stubbed for a branch that will reach it. */
    private void runCycleReachingUsersAt(String instant, ZoneId zone) {
        user.setTimezone(zone.getId());
        ReflectionTestUtils.setField(scheduler, "clock",
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        when(userRepository.findDistinctTimezones()).thenReturn(List.of(zone.getId()));
        when(userRepository.findAllByTimezone(zone.getId())).thenReturn(List.of(user));
        scheduler.processSnapshots();
    }

    private static LocalDate localDateAt(String instant, ZoneId zone) {
        return Instant.parse(instant).atZone(zone).toLocalDate();
    }

    @Nested
    @DisplayName("Europe/Lisbon, the zone Beyou is deployed for")
    class Lisbon {

        @Test
        @DisplayName("summer (WEST, UTC+1): the day closes at 02:00 LOCAL, which is 01:00Z")
        void closesAtLocalTwoInSummer() {
            String instant = "2026-08-21T01:00:00Z";
            assertThat(ZonedDateTime.ofInstant(Instant.parse(instant), LISBON).getHour())
                    .as("the local hour the cycle observes")
                    .isEqualTo(DAY_CLOSE_GRACE_HOUR);

            runCycleReachingUsersAt(instant, LISBON);

            // Local 2026-08-21 02:00, so the day that just ended locally is the 20th.
            verify(dayCloseService).closeDay(user, LocalDate.of(2026, 8, 20));
        }

        @Test
        @DisplayName("winter (WET, UTC+0): local 02:00 is 02:00Z, and nothing else moves")
        void closesAtLocalTwoInWinter() {
            String instant = "2026-01-21T02:00:00Z";
            assertThat(ZonedDateTime.ofInstant(Instant.parse(instant), LISBON).getHour())
                    .isEqualTo(DAY_CLOSE_GRACE_HOUR);

            runCycleReachingUsersAt(instant, LISBON);

            verify(dayCloseService).closeDay(user, LocalDate.of(2026, 1, 20));
        }

        @Test
        @DisplayName("summer: 01:00 LOCAL is too early, the day is still running")
        void doesNotCloseAnHourEarly() {
            // 00:00Z is 01:00 in Lisbon. Under the old stored 'UTC' this instant looked
            // like midnight and triggered the SNAPSHOT of a day with an hour left in it.
            runCycleAt("2026-08-21T00:00:00Z", LISBON);

            verifyNoInteractions(dayCloseService);
        }

        @Test
        @DisplayName("the snapshot fires at 00:00 LOCAL, not at 00:00Z")
        void snapshotsAtLocalMidnight() {
            // 23:00Z on the 20th is 00:00 on the 21st in Lisbon.
            user.setTimezone(LISBON.getId());
            ReflectionTestUtils.setField(scheduler, "clock",
                    Clock.fixed(Instant.parse("2026-08-20T23:00:00Z"), ZoneOffset.UTC));
            when(userRepository.findDistinctTimezones()).thenReturn(List.of(LISBON.getId()));
            when(userRepository.findAllByTimezone(LISBON.getId())).thenReturn(List.of(user));
            when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(new ArrayList<>());

            scheduler.processSnapshots();

            // The snapshot branch ran (it is the only one active at local hour 0) and the
            // close branch did not.
            verify(userRepository).findAllByTimezone(LISBON.getId());
            verifyNoInteractions(dayCloseService);
        }

        @Test
        @DisplayName("the spring-forward day still closes exactly once, for the right day")
        void survivesTheSpringForwardDay() {
            // Lisbon changes over on 2026-03-29, and the hour it skips is 01:00, not the
            // grace hour — unlike America/New_York, which skips 02:00 and is why the close
            // window spans two hours. Pinned so a change to either the window or the
            // tzdata shows up here rather than as a silently unclosed day.
            assertThat(localHoursOn(LocalDate.of(2026, 3, 29), LISBON))
                    .as("local hours that exist on the Lisbon changeover day")
                    .doesNotContain(1)
                    .contains(DAY_CLOSE_GRACE_HOUR);

            runCycleReachingUsersAt("2026-03-29T01:00:00Z", LISBON);

            verify(dayCloseService, times(1)).closeDay(user, LocalDate.of(2026, 3, 28));
        }
    }

    @Nested
    @DisplayName("America/Sao_Paulo, a three-hour offset")
    class SaoPaulo {

        @Test
        @DisplayName("closes at 02:00 LOCAL, which is 05:00Z")
        void closesAtLocalTwo() {
            String instant = "2026-08-21T05:00:00Z";
            assertThat(ZonedDateTime.ofInstant(Instant.parse(instant), SAO_PAULO).getHour())
                    .isEqualTo(DAY_CLOSE_GRACE_HOUR);

            runCycleReachingUsersAt(instant, SAO_PAULO);

            verify(dayCloseService).closeDay(user, LocalDate.of(2026, 8, 20));
        }

        @Test
        @DisplayName("does NOT close at 23:00 local, three hours before the day even ends")
        void doesNotCloseTheEveningBefore() {
            // 02:00Z is 23:00 the previous evening in São Paulo. This is the exact instant
            // that stamped MISSED on a day still in progress while the zone was stored as
            // UTC: the account's routine had three hours left to run.
            String instant = "2026-08-21T02:00:00Z";
            assertThat(ZonedDateTime.ofInstant(Instant.parse(instant), SAO_PAULO).getHour())
                    .as("23:00 the previous local evening")
                    .isEqualTo(23);

            runCycleAt(instant, SAO_PAULO);

            verifyNoInteractions(dayCloseService);
        }

        @Test
        @DisplayName("does NOT snapshot at 21:00 local, with the day still running")
        void doesNotSnapshotTheEveningBefore() {
            // 00:00Z is 21:00 the previous evening in São Paulo — the other half of the
            // same failure: a day photographed with three hours of life left in it.
            runCycleAt("2026-08-21T00:00:00Z", SAO_PAULO);

            verify(userRepository, never()).findAllByTimezone(anyString());
            verifyNoInteractions(dayCloseService);
        }
    }

    @Test
    @DisplayName("a user still on UTC behaves exactly as before")
    void utcIsUnchanged() {
        // The regression guard for the V20 backfill: rows left on UTC must not shift.
        ZoneId utc = ZoneId.of("UTC");
        runCycleReachingUsersAt("2026-08-21T02:00:00Z", utc);

        verify(dayCloseService).closeDay(user, LocalDate.of(2026, 8, 20));
    }

    @Test
    @DisplayName("a late-evening check lands on the day the close then reads")
    void lateCheckAndCloseAgreeOnTheDay() {
        // The two halves of the bug meeting. The check resolves its day through
        // UserDateResolver and the close is handed a day by the scheduler; if they
        // disagree, the check is filed under D+1 and D closes with nothing on it.
        user.setTimezone(LISBON.getId());

        // 22:30Z on the 20th is 23:30 local on the 20th, in summer.
        Clock lateEvening = Clock.fixed(Instant.parse("2026-08-20T22:30:00Z"), ZoneOffset.UTC);
        LocalDate checkedDay = UserDateResolver.today(user, lateEvening);

        // The close runs at local 02:00 the next morning and is handed the local yesterday.
        LocalDate closingDay = localDateAt("2026-08-21T01:00:00Z", LISBON).minusDays(1);

        assertThat(checkedDay)
                .as("the check's day and the closing day must be the same day")
                .isEqualTo(closingDay)
                .isEqualTo(LocalDate.of(2026, 8, 20));
    }

    private static List<Integer> localHoursOn(LocalDate day, ZoneId zone) {
        List<Integer> hours = new ArrayList<>();
        Instant cursor = day.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        for (int step = 0; step < 48; step++) {
            ZonedDateTime moment = cursor.plusSeconds(step * 3600L).atZone(zone);
            if (moment.toLocalDate().equals(day)) {
                hours.add(moment.getHour());
            }
        }
        return hours;
    }
}
