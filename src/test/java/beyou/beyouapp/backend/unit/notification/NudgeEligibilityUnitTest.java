package beyou.beyouapp.backend.unit.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.notification.engagement.NudgeDecision;
import beyou.beyouapp.backend.notification.engagement.NudgeEligibility;
import beyou.beyouapp.backend.notification.engagement.NudgeKind;

/**
 * Who gets a nudge, and — mostly — who does not.
 *
 * <p>Every condition in {@code NudgeEligibility} exists to keep a mail away from somebody
 * it would be wrong for, so most of these tests assert that nothing is sent. That is the
 * point: a trigger with no exclusions mails everybody, and the fastest way to make people
 * mark a sender as spam is to write to them about a situation they are not in.
 */
class NudgeEligibilityUnitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    private static final int BACKFILL_DAYS = 7;

    /** The day that stops being recoverable after today: yesterday minus (window - 1). */
    private static final LocalDate EXPIRING = TODAY.minusDays(1).minusDays(BACKFILL_DAYS - 1L);

    private static EntityCheckDay accountRow(LocalDate day, CheckDayOutcome outcome) {
        EntityCheckDay row = new EntityCheckDay();
        row.setOwnerType(CheckDayOwnerType.USER);
        row.setOwnerId(UUID.randomUUID());
        row.setDay(day);
        row.setOutcome(outcome);
        return row;
    }

    private static Optional<NudgeDecision> decide(List<EntityCheckDay> rows) {
        return decide(rows, false, false, 0, 0);
    }

    private static Optional<NudgeDecision> decide(
            List<EntityCheckDay> rows, boolean scheduledToday, boolean completedToday,
            int currentStreak, int bestStreak) {
        return NudgeEligibility.decide(TODAY, BACKFILL_DAYS, rows, 20,
                scheduledToday, completedToday, currentStreak, bestStreak, 3, 2);
    }

    @Nested
    @DisplayName("the expiring recovery window")
    class ExpiringWindow {

        @Test
        @DisplayName("fires for a missed day that falls out of the window tomorrow")
        void firesOnTheLastRecoverableDay() {
            Optional<NudgeDecision> decision = decide(List.of(accountRow(EXPIRING, CheckDayOutcome.MISSED)));

            assertThat(decision).isPresent();
            assertThat(decision.get().kind()).isEqualTo(NudgeKind.XP_RECOVERY_WINDOW);
            assertThat(decision.get().expiringDay()).isEqualTo(EXPIRING);
            assertThat(decision.get().remainingXpPercent())
                    .as("the mail quotes what a late check still earns")
                    .isEqualTo(20);
        }

        /**
         * A missed day in the middle of the window is recoverable tomorrow too. Mailing
         * about it every day until it expires is how a useful nudge becomes noise.
         */
        @Test
        @DisplayName("stays quiet for a missed day that is not expiring yet")
        void ignoresDaysStillComfortablyInsideTheWindow() {
            assertThat(decide(List.of(accountRow(TODAY.minusDays(2), CheckDayOutcome.MISSED)))).isEmpty();
        }

        /**
         * Past the window the day is gone: MAX_BACKFILL_DAYS means the snapshot job will
         * not accept a check for it. Telling somebody about it would be cruel and useless.
         */
        @Test
        @DisplayName("stays quiet for a day that has already fallen out")
        void ignoresDaysAlreadyGone() {
            assertThat(decide(List.of(accountRow(EXPIRING.minusDays(1), CheckDayOutcome.MISSED)))).isEmpty();
        }

        /**
         * The three non-MISSED outcomes each represent a day that needs no rescue, and
         * mailing about any of them invents a failure — the same mistake DayCloseService
         * refuses to make when it declines to stamp MISSED across a retroactive window.
         */
        @Test
        @DisplayName("only a missed day counts, never done, skipped or unscheduled")
        void onlyMissedCounts() {
            assertThat(decide(List.of(accountRow(EXPIRING, CheckDayOutcome.DONE)))).isEmpty();
            assertThat(decide(List.of(accountRow(EXPIRING, CheckDayOutcome.SKIPPED)))).isEmpty();
            assertThat(decide(List.of(accountRow(EXPIRING, CheckDayOutcome.NOT_SCHEDULED)))).isEmpty();
            assertThat(decide(List.of(accountRow(EXPIRING, CheckDayOutcome.NOT_IN_ROUTINE)))).isEmpty();
        }

        @Test
        @DisplayName("an account with no frozen rows gets nothing")
        void toleratesNoHistory() {
            assertThat(decide(List.of())).isEmpty();
            assertThat(decide(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the streak record")
    class StreakRecord {

        @Test
        @DisplayName("fires when a run near the record has an open scheduled day")
        void firesForARunNearTheRecord() {
            Optional<NudgeDecision> decision = decide(List.of(), true, false, 21, 23);

            assertThat(decision).isPresent();
            assertThat(decision.get().kind()).isEqualTo(NudgeKind.STREAK_RECORD_AT_RISK);
            assertThat(decision.get().currentStreak()).isEqualTo(21);
            assertThat(decision.get().bestStreak()).isEqualTo(23);
            assertThat(decision.get().isRecordWithinReach()).isFalse();
        }

        /**
         * The streak counts SCHEDULED days, so on an unscheduled day it cannot break. A
         * Mon/Wed/Fri user must not be told on a Tuesday that their streak ends today,
         * because it does not — and one mail like that destroys the credibility of every
         * later one.
         */
        @Test
        @DisplayName("stays quiet on a day nothing is scheduled")
        void neverFiresOnAnUnscheduledDay() {
            assertThat(decide(List.of(), false, false, 21, 23)).isEmpty();
        }

        @Test
        @DisplayName("stays quiet once today is already done")
        void neverFiresAfterTheDayIsComplete() {
            assertThat(decide(List.of(), true, true, 21, 23)).isEmpty();
        }

        /** A two-day run costs more goodwill to defend by mail than it is worth. */
        @Test
        @DisplayName("stays quiet for a run below the floor")
        void ignoresVeryShortRuns() {
            assertThat(decide(List.of(), true, false, 2, 30)).isEmpty();
            assertThat(decide(List.of(), true, false, 3, 3))
                    .as("exactly at the floor is worth defending")
                    .isPresent();
        }

        @Test
        @DisplayName("stays quiet for a run nowhere near the record")
        void ignoresRunsFarFromTheRecord() {
            assertThat(decide(List.of(), true, false, 5, 30)).isEmpty();
        }

        @Test
        @DisplayName("the record gap is inclusive at its edge")
        void honoursTheRecordGapBoundary() {
            assertThat(decide(List.of(), true, false, 21, 23))
                    .as("two below the record is inside the gap")
                    .isPresent();
            assertThat(decide(List.of(), true, false, 20, 23))
                    .as("three below is outside it")
                    .isEmpty();
        }

        /**
         * A run past the old record is setting a new one, and that is worth defending too —
         * with different words, which is why the flag exists rather than being derived in
         * the template.
         */
        @Test
        @DisplayName("a run at or past the record reads as a record within reach")
        void marksARecordWithinReach() {
            assertThat(decide(List.of(), true, false, 23, 23).orElseThrow().isRecordWithinReach()).isTrue();
            assertThat(decide(List.of(), true, false, 30, 23).orElseThrow().isRecordWithinReach()).isTrue();
        }
    }

    /**
     * Only one mail goes out per account per pass. The expiring window wins because its
     * deadline passes tonight, while a streak can be defended tomorrow as well.
     */
    @Test
    @DisplayName("when both fire, the expiring window wins")
    void prefersTheDeadlineThatPassesTonight() {
        Optional<NudgeDecision> decision =
                decide(List.of(accountRow(EXPIRING, CheckDayOutcome.MISSED)), true, false, 21, 23);

        assertThat(decision).isPresent();
        assertThat(decision.get().kind()).isEqualTo(NudgeKind.XP_RECOVERY_WINDOW);
    }

    @Test
    @DisplayName("an account with nothing going on gets nothing")
    void theCommonCaseIsSilence() {
        assertThat(decide(List.of(), true, false, 0, 0)).isEmpty();
    }
}
