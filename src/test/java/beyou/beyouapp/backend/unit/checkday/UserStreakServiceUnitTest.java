package beyou.beyouapp.backend.unit.checkday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.checkday.UserStreakService.UserStreak;
import beyou.beyouapp.backend.user.User;

/**
 * R14/R13/R20 — the account streak walk, in isolation.
 *
 * <p>The rules under test are the three-way classification of a day (completed / scheduled
 * and not completed / neutral), the floor that stops the walk, and the dormancy flag. The
 * arithmetic is a pure static function, so most of this needs no mock at all.
 */
class UserStreakServiceUnitTest {

    // A real Mon..Sun week, so "the days nothing was scheduled" are actual Tuesdays.
    private static final LocalDate MON = LocalDate.of(2026, 8, 3);
    private static final LocalDate TUE = LocalDate.of(2026, 8, 4);
    private static final LocalDate WED = LocalDate.of(2026, 8, 5);
    private static final LocalDate THU = LocalDate.of(2026, 8, 6);
    private static final LocalDate FRI = LocalDate.of(2026, 8, 7);
    private static final LocalDate SAT = LocalDate.of(2026, 8, 8);
    private static final LocalDate SUN = LocalDate.of(2026, 8, 9);

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        user.setTimezone("UTC");
    }

    private EntityCheckDay row(LocalDate day, CheckDayOutcome outcome) {
        return new EntityCheckDay(user, CheckDayOwnerType.USER, user.getId(), day, outcome);
    }

    /** The rows a Mon/Wed/Fri routine leaves behind once the week has closed. */
    private List<EntityCheckDay> monWedFriWeek() {
        return List.of(
                row(MON, CheckDayOutcome.MISSED),
                row(TUE, CheckDayOutcome.NOT_SCHEDULED),
                row(WED, CheckDayOutcome.MISSED),
                row(THU, CheckDayOutcome.NOT_SCHEDULED),
                row(FRI, CheckDayOutcome.MISSED),
                row(SAT, CheckDayOutcome.NOT_SCHEDULED));
    }

    private static Set<LocalDate> completed(LocalDate... days) {
        return new HashSet<>(Set.of(days));
    }

    @Nested
    class TheWalk {

        @Test
        void countsScheduledDaysAndStepsOverTheRest() {
            UserStreak streak = UserStreakService.walk(
                    completed(MON, WED, FRI), monWedFriWeek(), FRI);

            assertThat(streak.currentStreak()).isEqualTo(3);
        }

        @Test
        void endsOnlyOnADayThatWasScheduledAndNotCompleted() {
            UserStreak streak = UserStreakService.walk(
                    completed(MON, FRI), monWedFriWeek(), FRI);

            assertThat(streak.currentStreak())
                    .as("Wednesday was scheduled and left undone")
                    .isEqualTo(1);
        }

        @Test
        void survivesAReferenceDayWellPastTheLastCompletedDay() {
            // The old rule returned zero outright whenever the reference day sat more than
            // one day past the last completed one, before the walk was ever entered.
            UserStreak streak = UserStreakService.walk(
                    completed(MON, WED, FRI), monWedFriWeek(), SUN);

            assertThat(streak.currentStreak()).isEqualTo(3);
        }

        @Test
        void treatsADayWithNoRowAsNeutral() {
            // R18 — a night the day-close pass never ran leaves no row. Unknown is not
            // failed; one outage must not read back as a broken streak for everybody.
            List<EntityCheckDay> partial = List.of(
                    row(MON, CheckDayOutcome.MISSED),
                    row(WED, CheckDayOutcome.MISSED));

            UserStreak streak = UserStreakService.walk(completed(MON, WED), partial, WED);

            assertThat(streak.currentStreak()).isEqualTo(2);
        }

        @Test
        void treatsAnAccountWithNoRoutinesAsNeutralThroughout() {
            List<EntityCheckDay> orphaned = List.of(
                    row(MON, CheckDayOutcome.NOT_IN_ROUTINE),
                    row(TUE, CheckDayOutcome.NOT_IN_ROUTINE),
                    row(WED, CheckDayOutcome.NOT_IN_ROUTINE));

            UserStreak streak = UserStreakService.walk(completed(MON), orphaned, THU);

            assertThat(streak.currentStreak())
                    .as("Nothing was ever expected, so nothing was ever failed")
                    .isEqualTo(1);
        }

        @Test
        void stopsAtTheEarliestCompletedDayWhenEveryGapDayIsNeutral() {
            // No terminating day exists in the outcomes, so the floor is the only thing
            // ending this. Without it the loop runs forever, on the login path and inside
            // the check transaction both.
            List<EntityCheckDay> allNeutral = new ArrayList<>();
            for (LocalDate day = MON.minusYears(2); !day.isAfter(SUN); day = day.plusDays(1)) {
                allNeutral.add(row(day, CheckDayOutcome.NOT_SCHEDULED));
            }

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                UserStreak streak = UserStreakService.walk(completed(FRI), allNeutral, SUN);
                assertThat(streak.currentStreak()).isEqualTo(1);
            });
        }

        @Test
        void countsUnorderedCompletedDaysTheSameWay() {
            Set<LocalDate> outOfOrder = new HashSet<>();
            outOfOrder.add(FRI);
            outOfOrder.add(MON);
            outOfOrder.add(WED);

            assertThat(UserStreakService.walk(outOfOrder, monWedFriWeek(), FRI).currentStreak())
                    .isEqualTo(3);
        }

        @Test
        void ignoresRowsForDaysAfterTheReferenceDay() {
            // A back-dated read must report the run as it stood then, not be broken by a
            // day that had not happened yet.
            UserStreak streak = UserStreakService.walk(
                    completed(MON, WED), monWedFriWeek(), WED);

            assertThat(streak.currentStreak()).isEqualTo(2);
        }

        @Test
        void reportsZeroForAUserWithNoHistoryRatherThanThrowing() {
            assertThat(UserStreakService.walk(Set.of(), monWedFriWeek(), SUN))
                    .isEqualTo(UserStreak.NONE);
            assertThat(UserStreakService.walk(null, monWedFriWeek(), SUN))
                    .isEqualTo(UserStreak.NONE);
            assertThat(UserStreakService.walk(completed(MON), monWedFriWeek(), null))
                    .isEqualTo(UserStreak.NONE);
            assertThat(UserStreakService.walk(completed(MON), null, MON).currentStreak())
                    .isEqualTo(1);
        }

        @Test
        void toleratesHalfWrittenRows() {
            List<EntityCheckDay> ragged = new ArrayList<>();
            ragged.add(null);
            ragged.add(row(null, CheckDayOutcome.MISSED));
            ragged.add(row(WED, null));
            ragged.add(row(MON, CheckDayOutcome.MISSED));

            assertThat(UserStreakService.walk(completed(MON, WED), ragged, WED).currentStreak())
                    .isEqualTo(2);
        }
    }

    @Nested
    class Dormancy {

        @Test
        void flagsAStreakWithNothingScheduledForFourteenDays() {
            LocalDate lastScheduled = SUN.minusDays(UserStreakService.DORMANT_AFTER_DAYS);
            List<EntityCheckDay> rows = List.of(
                    row(lastScheduled, CheckDayOutcome.MISSED),
                    row(SUN.minusDays(1), CheckDayOutcome.NOT_IN_ROUTINE));

            UserStreak streak = UserStreakService.walk(completed(lastScheduled), rows, SUN);

            assertThat(streak.currentStreak())
                    .as("R20 flags the run, it does not erase it")
                    .isEqualTo(1);
            assertThat(streak.dormant()).isTrue();
        }

        @Test
        void doesNotFlagAStreakStillInsideTheWindow() {
            LocalDate lastScheduled = SUN.minusDays(UserStreakService.DORMANT_AFTER_DAYS - 1);
            List<EntityCheckDay> rows = List.of(
                    row(lastScheduled, CheckDayOutcome.MISSED),
                    row(SUN.minusDays(1), CheckDayOutcome.NOT_IN_ROUTINE));

            UserStreak streak = UserStreakService.walk(completed(lastScheduled), rows, SUN);

            assertThat(streak.dormant()).isFalse();
        }

        @Test
        void doesNotFlagAnAccountWhoseDaysHaveNotBeenClosedYet() {
            // No rows at all — the day-close pass has not run. An absent row is unknown,
            // never "nothing was scheduled", and a user who completed this morning is the
            // least dormant thing there is.
            UserStreak streak = UserStreakService.walk(completed(SUN), List.of(), SUN);

            assertThat(streak.currentStreak()).isEqualTo(1);
            assertThat(streak.dormant()).isFalse();
        }

        @Test
        void doesNotFlagAnAccountThatCompletedADayInsideTheWindow() {
            // Completion is activity whatever the row says — a habit checked outside any
            // routine leaves a NOT_IN_ROUTINE row and still means the user showed up.
            List<EntityCheckDay> rows = new ArrayList<>();
            for (int back = 0; back < 30; back++) {
                rows.add(row(SUN.minusDays(back), CheckDayOutcome.NOT_IN_ROUTINE));
            }

            UserStreak streak = UserStreakService.walk(completed(SUN.minusDays(3)), rows, SUN);

            assertThat(streak.dormant()).isFalse();
        }

        @Test
        void neverFlagsAnAccountThatHasNoStreakToPause() {
            // A brand-new account collects fourteen NOT_IN_ROUTINE rows on its own. Zero is
            // already the whole story; "dormant" would be the only thing this ever said.
            List<EntityCheckDay> rows = new ArrayList<>();
            for (int back = 0; back < 30; back++) {
                rows.add(row(SUN.minusDays(back), CheckDayOutcome.NOT_IN_ROUTINE));
            }

            UserStreak streak = UserStreakService.walk(Set.of(), rows, SUN);

            assertThat(streak.currentStreak()).isZero();
            assertThat(streak.dormant()).isFalse();
        }
    }

    @Nested
    class TheRepositoryBackedEntryPoint {

        private EntityCheckDayRepository repository;
        private UserStreakService service;

        @BeforeEach
        void wire() {
            repository = mock(EntityCheckDayRepository.class);
            service = new UserStreakService(repository);
        }

        @Test
        void readsOnlyTheAccountsOwnRows() {
            when(repository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                    CheckDayOwnerType.USER, user.getId())).thenReturn(monWedFriWeek());
            user.setCompletedDays(completed(MON, WED, FRI));

            assertThat(service.streakOf(user, FRI).currentStreak()).isEqualTo(3);
        }

        @Test
        void skipsTheQueryEntirelyForAnAccountWithNoCompletedDays() {
            // This runs on the login path; a fresh user must not pay for a read that can
            // only ever come back as zero.
            user.setCompletedDays(new HashSet<>());

            assertThat(service.streakOf(user, SUN)).isEqualTo(UserStreak.NONE);
            verifyNoInteractions(repository);
        }

        @Test
        void fallsBackToTheOwnersTodayWhenNoReferenceDayIsGiven() {
            // R15 — the owner's zone, never the server's.
            when(repository.findByOwnerTypeAndOwnerIdOrderByDayAsc(any(), any()))
                    .thenReturn(List.of());
            user.setTimezone("Etc/GMT-14");
            LocalDate ownerToday = LocalDate.now(java.time.ZoneId.of("Etc/GMT-14"));
            user.setCompletedDays(completed(ownerToday));

            assertThat(service.streakOf(user).currentStreak()).isEqualTo(1);
            assertThat(service.streakOf(user, null).currentStreak()).isEqualTo(1);
        }

        @Test
        void answersZeroForANullUserRatherThanThrowing() {
            assertThat(service.streakOf(null)).isEqualTo(UserStreak.NONE);
            assertThat(service.streakOf(null, SUN)).isEqualTo(UserStreak.NONE);
            verifyNoInteractions(repository);
        }
    }
}
