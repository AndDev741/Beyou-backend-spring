package beyou.beyouapp.backend.unit.checkday;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckProgressCalculator;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.user.User;

/**
 * The arithmetic, with no database and no mocks — the calculator is a pure function over a
 * list of rows, which is the whole point of splitting it away from the recorder.
 */
class CheckProgressCalculatorUnitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 20);
    private static final UUID OWNER = UUID.randomUUID();
    private static final User USER = new User();

    @Nested
    class Totals {

        @Test
        void countsOnlyDoneRowsAndTakesTheDatesFromTheirExtremes() {
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(4), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(3), CheckDayOutcome.SKIPPED),
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(1), CheckDayOutcome.NOT_SCHEDULED),
                    row(TODAY, CheckDayOutcome.DONE));

            CheckProgress progress = CheckProgressCalculator.recompute(rows, TODAY, 0);

            assertThat(progress.getTotalCheckIns()).isEqualTo(3);
            assertThat(progress.getFirstCheckInDate()).isEqualTo(TODAY.minusDays(4));
            assertThat(progress.getLastCheckInDate()).isEqualTo(TODAY);
        }

        @Test
        void anOwnerWithNoRowsHasNothingAtAll() {
            CheckProgress progress = CheckProgressCalculator.recompute(List.of(), TODAY, 0);

            assertThat(progress.getTotalCheckIns()).isZero();
            assertThat(progress.getCurrentStreak()).isZero();
            assertThat(progress.getBestStreak()).isZero();
            assertThat(progress.getFirstCheckInDate()).isNull();
            assertThat(progress.getLastCheckInDate()).isNull();
        }

        @Test
        void aHistoryWithNoDoneRowLeavesTheCheckInDatesNull() {
            CheckProgress progress = CheckProgressCalculator.recompute(
                    rows(row(TODAY, CheckDayOutcome.SKIPPED), row(TODAY.minusDays(1), CheckDayOutcome.MISSED)),
                    TODAY, 0);

            assertThat(progress.getTotalCheckIns()).isZero();
            assertThat(progress.getFirstCheckInDate()).isNull();
            assertThat(progress.getLastCheckInDate()).isNull();
        }

        @Test
        void aNullRowListIsTreatedAsAnEmptyOne() {
            CheckProgress progress = CheckProgressCalculator.recompute(null, TODAY, 4);

            assertThat(progress.getTotalCheckIns()).isZero();
            assertThat(progress.getCurrentStreak()).isZero();
            assertThat(progress.getBestStreak()).isEqualTo(4);
        }

        @Test
        void rowsArriveInAnyOrderAndTheDatesStillComeOutRight() {
            List<EntityCheckDay> shuffled = rows(
                    row(TODAY, CheckDayOutcome.DONE),
                    row(TODAY.minusDays(5), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE));
            Collections.reverse(shuffled);

            CheckProgress progress = CheckProgressCalculator.recompute(shuffled, TODAY, 0);

            assertThat(progress.getFirstCheckInDate()).isEqualTo(TODAY.minusDays(5));
            assertThat(progress.getLastCheckInDate()).isEqualTo(TODAY);
            assertThat(progress.getTotalCheckIns()).isEqualTo(3);
        }
    }

    @Nested
    class TheWalk {

        @Test
        void consecutiveDoneDaysCount() {
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(1), CheckDayOutcome.DONE),
                    row(TODAY, CheckDayOutcome.DONE));

            assertThat(CheckProgressCalculator.recompute(rows, TODAY, 0).getCurrentStreak()).isEqualTo(3);
        }

        @Test
        void aSkippedDayCarriesTheStreakThroughWithoutCounting() {
            // R12 — the skip is deliberate, so it is not a break, but it is not a check-in
            // either. Five done days with a skip in the middle is a streak of five, not six.
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(5), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(4), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(3), CheckDayOutcome.SKIPPED),
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(1), CheckDayOutcome.DONE),
                    row(TODAY, CheckDayOutcome.DONE));

            CheckProgress progress = CheckProgressCalculator.recompute(rows, TODAY, 0);

            assertThat(progress.getCurrentStreak()).isEqualTo(5);
            assertThat(progress.getTotalCheckIns()).isEqualTo(5);
        }

        @Test
        void aStreakOfFiveSurvivesAnUnscheduledYesterday() {
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(5), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(4), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(3), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(1), CheckDayOutcome.NOT_SCHEDULED),
                    row(TODAY, CheckDayOutcome.DONE));

            assertThat(CheckProgressCalculator.recompute(rows, TODAY, 0).getCurrentStreak()).isEqualTo(5);
        }

        @Test
        void aDayTheOwnerBelongedToNoRoutineIsAlsoNeutral() {
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(1), CheckDayOutcome.NOT_IN_ROUTINE),
                    row(TODAY, CheckDayOutcome.DONE));

            assertThat(CheckProgressCalculator.recompute(rows, TODAY, 0).getCurrentStreak()).isEqualTo(2);
        }

        @Test
        void aDayWithNoRowAtAllIsNeutralAndTheStreakSpansIt() {
            // R18/KTD19 — the day-close pass never ran for that day. Unknown is not failed:
            // a night of scheduler downtime must not read back as a broken streak.
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(3), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    // nothing at all for TODAY - 1
                    row(TODAY, CheckDayOutcome.DONE));

            assertThat(CheckProgressCalculator.recompute(rows, TODAY, 0).getCurrentStreak()).isEqualTo(3);
        }

        @Test
        void aMissedDayEndsTheWalkAtTheLaterRun() {
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(6), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(5), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(4), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(3), CheckDayOutcome.MISSED),
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(1), CheckDayOutcome.DONE),
                    row(TODAY, CheckDayOutcome.DONE));

            CheckProgress progress = CheckProgressCalculator.recompute(rows, TODAY, 0);

            assertThat(progress.getCurrentStreak()).isEqualTo(3);
            assertThat(progress.getTotalCheckIns()).isEqualTo(6);
        }

        @Test
        void aMissedReferenceDayIsAStreakOfZeroEvenOnTopOfALongRun() {
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(1), CheckDayOutcome.DONE),
                    row(TODAY, CheckDayOutcome.MISSED));

            assertThat(CheckProgressCalculator.recompute(rows, TODAY, 0).getCurrentStreak()).isZero();
        }

        @Test
        void anAllNeutralHistoryTerminatesRatherThanWalkingForever() {
            // Without the earliest-stored-row floor this loop never ends: every day the walk
            // reaches is neutral, including the infinite tail of days with no row at all.
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(2), CheckDayOutcome.NOT_SCHEDULED),
                    row(TODAY.minusDays(1), CheckDayOutcome.SKIPPED),
                    row(TODAY, CheckDayOutcome.NOT_IN_ROUTINE));

            assertThat(CheckProgressCalculator.recompute(rows, TODAY, 0).getCurrentStreak()).isZero();
        }

        @Test
        void aReferenceDayBeforeEveryStoredRowHasNoStreak() {
            List<EntityCheckDay> rows = rows(
                    row(TODAY, CheckDayOutcome.DONE),
                    row(TODAY.plusDays(1), CheckDayOutcome.DONE));

            assertThat(CheckProgressCalculator.recompute(rows, TODAY.minusDays(1), 0).getCurrentStreak())
                    .isZero();
        }

        @Test
        void theWalkStartsAtTheReferenceDayAndIgnoresAnythingAfterIt() {
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(1), CheckDayOutcome.DONE),
                    row(TODAY, CheckDayOutcome.DONE),
                    row(TODAY.plusDays(1), CheckDayOutcome.DONE));

            assertThat(CheckProgressCalculator.recompute(rows, TODAY, 0).getCurrentStreak()).isEqualTo(2);
        }
    }

    @Nested
    class TheRecord {

        @Test
        void risesWhenTheCurrentStreakPassesIt() {
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(1), CheckDayOutcome.DONE),
                    row(TODAY, CheckDayOutcome.DONE));

            CheckProgress progress = CheckProgressCalculator.recompute(rows, TODAY, 2);

            assertThat(progress.getCurrentStreak()).isEqualTo(3);
            assertThat(progress.getBestStreak()).isEqualTo(3);
        }

        @Test
        void staysPutWhenTheCurrentStreakLaterFalls() {
            // R13 — a broken streak must not erase the record of the best one.
            List<EntityCheckDay> rows = rows(
                    row(TODAY.minusDays(1), CheckDayOutcome.MISSED),
                    row(TODAY, CheckDayOutcome.DONE));

            CheckProgress progress = CheckProgressCalculator.recompute(rows, TODAY, 12);

            assertThat(progress.getCurrentStreak()).isEqualTo(1);
            assertThat(progress.getBestStreak()).isEqualTo(12);
        }

        @Test
        void aNonsenseNegativeRecordIsFlooredAtZeroRatherThanCarriedForward() {
            CheckProgress progress = CheckProgressCalculator.recompute(List.of(), TODAY, -3);

            assertThat(progress.getBestStreak()).isZero();
        }
    }

    @Test
    void aDuplicateDayIsResolvedRatherThanCountedTwice() {
        // The unique constraint makes this impossible in the database. If a caller ever
        // hands over a stale copy alongside a fresh one, the later entry wins and the total
        // still reads one, rather than silently double-counting.
        List<EntityCheckDay> rows = rows(
                row(TODAY, CheckDayOutcome.SKIPPED),
                row(TODAY, CheckDayOutcome.DONE));

        CheckProgress progress = CheckProgressCalculator.recompute(rows, TODAY, 0);

        assertThat(progress.getTotalCheckIns()).isEqualTo(1);
        assertThat(progress.getCurrentStreak()).isEqualTo(1);
    }

    @Test
    void rowsMissingADayOrAnOutcomeAreIgnoredRatherThanCrashing() {
        EntityCheckDay dayless = new EntityCheckDay(USER, CheckDayOwnerType.HABIT, OWNER, null, CheckDayOutcome.DONE);
        EntityCheckDay outcomeless = new EntityCheckDay(USER, CheckDayOwnerType.HABIT, OWNER, TODAY.minusDays(1), null);

        List<EntityCheckDay> rows = rows(row(TODAY, CheckDayOutcome.DONE));
        rows.add(dayless);
        rows.add(outcomeless);
        rows.add(null);

        CheckProgress progress = CheckProgressCalculator.recompute(rows, TODAY, 0);

        assertThat(progress.getTotalCheckIns()).isEqualTo(1);
        assertThat(progress.getCurrentStreak()).isEqualTo(1);
    }

    /**
     * R20/KTD25 — the dormancy read the habit and task responses ship as a flag. Derived
     * from the scalars alone so the {@code @Cacheable} habit list gains no query per habit;
     * the query-count test holds the other end of that.
     */
    @Nested
    class Dormancy {

        @Test
        void aLiveRunWithNoCheckInForTheWholeWindowIsDormant() {
            CheckProgress progress = progress(3, TODAY.minusDays(20));

            assertThat(CheckProgressCalculator.isDormant(progress, TODAY)).isTrue();
        }

        @Test
        void aCheckInOnTheOldestDayStillInsideTheWindowIsNotDormant() {
            // The window is inclusive of today and DORMANT_AFTER_DAYS days wide, matching
            // UserStreakService.activeRecently — the boundary day counts as activity.
            CheckProgress progress = progress(3,
                    TODAY.minusDays(UserStreakService.DORMANT_AFTER_DAYS - 1L));

            assertThat(CheckProgressCalculator.isDormant(progress, TODAY)).isFalse();
        }

        @Test
        void oneDayPastTheWindowTipsItOver() {
            CheckProgress progress = progress(3,
                    TODAY.minusDays(UserStreakService.DORMANT_AFTER_DAYS));

            assertThat(CheckProgressCalculator.isDormant(progress, TODAY)).isTrue();
        }

        @Test
        void aRunAtZeroIsNotDormantItIsSimplyAtZero() {
            // Otherwise every habit anyone ever abandoned would read as "paused", and the
            // flag would stop meaning anything.
            CheckProgress progress = progress(0, TODAY.minusDays(90));

            assertThat(CheckProgressCalculator.isDormant(progress, TODAY)).isFalse();
        }

        @Test
        void anEntityThatHasNeverBeenCheckedIsNotDormant() {
            CheckProgress progress = progress(0, null);

            assertThat(CheckProgressCalculator.isDormant(progress, TODAY)).isFalse();
        }

        @Test
        void aNullEmbeddableReadsAsNotDormantRatherThanThrowing() {
            // A row written before V13 comes back with no CheckProgress at all — the same
            // trap HabitMapper guards for xpProgress.
            assertThat(CheckProgressCalculator.isDormant(null, TODAY)).isFalse();
            assertThat(CheckProgressCalculator.isDormant(progress(3, TODAY.minusDays(90)), null))
                    .isFalse();
        }

        private CheckProgress progress(int currentStreak, LocalDate lastCheckIn) {
            CheckProgress progress = new CheckProgress();
            progress.setCurrentStreak(currentStreak);
            progress.setLastCheckInDate(lastCheckIn);
            return progress;
        }
    }

    private static List<EntityCheckDay> rows(EntityCheckDay... rows) {
        return new ArrayList<>(List.of(rows));
    }

    private static EntityCheckDay row(LocalDate day, CheckDayOutcome outcome) {
        return new EntityCheckDay(USER, CheckDayOwnerType.HABIT, OWNER, day, outcome);
    }
}
