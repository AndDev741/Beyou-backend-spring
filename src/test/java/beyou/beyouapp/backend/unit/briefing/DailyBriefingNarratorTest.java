package beyou.beyouapp.backend.unit.briefing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.briefing.DailyBriefingFactsBuilder;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingNarrator;
import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;
import beyou.beyouapp.backend.domain.briefing.dto.OpenItem;
import beyou.beyouapp.backend.domain.briefing.dto.RecoveryWindow;
import beyou.beyouapp.backend.domain.briefing.dto.TodayAhead;
import beyou.beyouapp.backend.domain.briefing.dto.YesterdayRecap;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;

/**
 * The two halves of the narrator that do not need a model: what goes INTO the prompt, and
 * what is allowed back out of it.
 *
 * <p>Worth testing separately because they are the whole defence. Everything the model is
 * told has already been computed, so a prompt missing a fact silently produces vague prose;
 * and anything the model returns is rendered, so an unbounded line becomes a panel that
 * overflows on a phone.
 */
class DailyBriefingNarratorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    // ---- sanitize ----

    @Test
    void sanitize_keepsAtMostThreeLines() {
        List<String> cleaned = DailyBriefingNarrator.sanitize(
                List.of("one", "two", "three", "four", "five"));

        assertThat(cleaned).containsExactly("one", "two", "three");
    }

    @Test
    void sanitize_dropsNullsAndBlanks() {
        List<String> cleaned = DailyBriefingNarrator.sanitize(
                Arrays.asList("kept", null, "   ", "\n", "also kept"));

        assertThat(cleaned).containsExactly("kept", "also kept");
    }

    /**
     * Truncated rather than dropped. A line one character over is still a usable line, and
     * refusing the whole response over a formatting miss throws away a good answer.
     */
    @Test
    void sanitize_truncatesRatherThanRejects() {
        String tooLong = "x".repeat(500);

        List<String> cleaned = DailyBriefingNarrator.sanitize(List.of(tooLong));

        assertThat(cleaned).hasSize(1);
        assertThat(cleaned.get(0)).hasSize(220);
    }

    @Test
    void sanitize_handlesNothingAtAll() {
        assertThat(DailyBriefingNarrator.sanitize(null)).isEmpty();
    }

    // ---- the prompt's facts block ----

    /**
     * The one thing the prompt must never leave implicit. A model told only "3 skipped" will
     * write an apology; the product's rule is that a skip is a choice and costs nothing.
     */
    @Test
    void factsMessage_saysOutrightThatASkipIsNotAFailure() {
        String message = DailyBriefingNarrator.factsMessage(factsWithYesterday(
                recap(true, false, 2, 3, 40d, List.of(openItem("Read", YESTERDAY)))));

        assertThat(message).contains("deliberately skipped: 3");
        assertThat(message).contains("never a failure");
    }

    /**
     * Same shape of rule at the other end of the day. An unscheduled day cannot break a
     * streak, because the streak counts scheduled days — a model left to guess writes
     * "don't lose your streak today" to somebody who has nothing on.
     */
    @Test
    void factsMessage_saysNothingIsAtRiskOnAnUnscheduledDay() {
        String message = DailyBriefingNarrator.factsMessage(new DailyBriefingFactsBuilder.Facts(
                recap(true, true, 4, 0, 80d, List.of()),
                new TodayAhead(0, false, 6, 9, List.of(), null)));

        assertThat(message).contains("No routine covers today");
        assertThat(message).contains("nothing is at risk");
    }

    @Test
    void factsMessage_carriesAnOverdueGoalAsOverdue() {
        GoalAhead overdue = new GoalAhead(UUID.randomUUID(), "Read 12 books", "book",
                7d, 12d, "books", TODAY.minusDays(4), -4, 58);

        String message = DailyBriefingNarrator.factsMessage(new DailyBriefingFactsBuilder.Facts(
                recap(true, false, 1, 0, 10d, List.of()),
                new TodayAhead(3, true, 2, 5, List.of(overdue), null)));

        assertThat(message).contains("overdue by 4 days");
        assertThat(message).contains("58% done");
        // Whole numbers read as counts, not as 7.0 of 12.0.
        assertThat(message).contains("7 of 12 books");
    }

    @Test
    void factsMessage_carriesTheRecoveryDeadlineAndWhatItIsWorth() {
        RecoveryWindow window = new RecoveryWindow(TODAY.minusDays(7), 1, 20,
                List.of(openItem("Stretch", TODAY.minusDays(7))));

        String message = DailyBriefingNarrator.factsMessage(new DailyBriefingFactsBuilder.Facts(
                recap(true, false, 0, 0, 0d, List.of()),
                new TodayAhead(2, true, 1, 3, List.of(), window)));

        assertThat(message).contains("stops being checkable in 1 days");
        assertThat(message).contains("worth 20% of its XP");
    }

    /** A day nothing was asked on must not be described as a day something was missed on. */
    @Test
    void factsMessage_distinguishesAnEmptyDayFromAMissedOne() {
        String message = DailyBriefingNarrator.factsMessage(factsWithYesterday(
                new YesterdayRecap(YESTERDAY, false, false, 0, 0, 0d, List.of(), 0, null)));

        assertThat(message).contains("No routine covered the day");
        assertThat(message).doesNotContain("still open");
    }

    @Test
    void factsMessage_namesOpenItemsButStopsCounting() {
        List<OpenItem> many = List.of(
                openItem("A", YESTERDAY), openItem("B", YESTERDAY), openItem("C", YESTERDAY),
                openItem("D", YESTERDAY), openItem("E", YESTERDAY), openItem("F", YESTERDAY),
                openItem("G", YESTERDAY), openItem("H", YESTERDAY));

        String message = DailyBriefingNarrator.factsMessage(factsWithYesterday(
                recap(true, false, 0, 0, 0d, many)));

        assertThat(message).contains("A, B, C, D, E, F and 2 more");
    }

    // ---- helpers ----

    private static DailyBriefingFactsBuilder.Facts factsWithYesterday(YesterdayRecap yesterday) {
        return new DailyBriefingFactsBuilder.Facts(
                yesterday, new TodayAhead(4, true, 3, 5, List.of(), null));
    }

    private static YesterdayRecap recap(boolean hadRoutine, boolean complete, int done,
                                        int skipped, double xp, List<OpenItem> open) {
        return new YesterdayRecap(YESTERDAY, hadRoutine, complete, done, skipped, xp, open, 0, null);
    }

    private static OpenItem openItem(String name, LocalDate date) {
        return new OpenItem(UUID.randomUUID(), UUID.randomUUID(), date, UUID.randomUUID(),
                "Morning", SnapshotItemType.HABIT, name, "icon", "Warm-up", 8d);
    }
}
