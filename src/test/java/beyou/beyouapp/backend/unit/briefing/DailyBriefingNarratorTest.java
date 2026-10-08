package beyou.beyouapp.backend.unit.briefing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.briefing.DailyBriefingFactsBuilder;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingNarrator;
import beyou.beyouapp.backend.domain.briefing.WeekSignals;
import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;
import beyou.beyouapp.backend.domain.briefing.dto.GoalPace;
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

    // ---- what is on screen stays out of the conversation ----

    /**
     * The regression this rewrite exists for. Yesterday's open items used to be named and
     * counted in the prompt, so the model wrote "20 items are still open, including Reading"
     * at 06:20 and the user had checked fifteen of them by 06:22. Those numbers are on the
     * left panel and change under the user's finger; the prose must not carry them.
     */
    @Test
    void factsMessage_leavesYesterdaysOpenItemsOut() {
        List<OpenItem> open = List.of(openItem("Evening reading", YESTERDAY),
                openItem("Stretch", YESTERDAY));

        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, false, 3, 2, 118d, open), today(List.of(), null), WeekSignals.empty()));

        assertThat(message).doesNotContain("Evening reading");
        assertThat(message).doesNotContain("Stretch");
        assertThat(message).doesNotContain("118");
        assertThat(message).doesNotContainIgnoringCase("still open");
    }

    /**
     * Context the model needs for tone is labelled as already on screen, so the instruction
     * not to restate it has something to point at.
     */
    @Test
    void factsMessage_labelsTheScreenAsContextAndTheSignalsAsTheSubject() {
        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, true, 4, 0, 80d, List.of()), today(List.of(), null), WeekSignals.empty()));

        assertThat(message).contains("ALREADY ON SCREEN (context only, do not restate)");
        assertThat(message).contains("SIGNALS (what to talk about)");
        assertThat(message.indexOf("ALREADY ON SCREEN")).isLessThan(message.indexOf("SIGNALS"));
    }

    /** A model told only "3 skipped" writes an apology; the product's rule is that a skip is a choice. */
    @Test
    void factsMessage_saysOutrightThatASkipIsNotAFailure() {
        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, false, 2, 3, 40d, List.of()), today(List.of(), null), WeekSignals.empty()));

        assertThat(message).contains("never a failure");
    }

    /**
     * Same shape of rule at the other end of the day. An unscheduled day cannot break a
     * streak, because the streak counts scheduled days — a model left to guess writes
     * "don't lose your streak today" to somebody who has nothing on.
     */
    @Test
    void factsMessage_saysNothingIsAtRiskOnAnUnscheduledDay() {
        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, true, 4, 0, 80d, List.of()),
                new TodayAhead(0, false, 6, 9, List.of(), null, List.of()),
                WeekSignals.empty()));

        assertThat(message).contains("No routine covers today");
        assertThat(message).contains("nothing is at risk");
    }

    /** A day nothing was asked on must not be described as a day something was missed on. */
    @Test
    void factsMessage_distinguishesAnEmptyDayFromAMissedOne() {
        String message = DailyBriefingNarrator.factsMessage(facts(
                new YesterdayRecap(YESTERDAY, false, false, 0, 0, 0d, List.of(), 0, null),
                today(List.of(), null), WeekSignals.empty()));

        assertThat(message).contains("no routine covered the day");
        assertThat(message).doesNotContain("Yesterday finished");
    }

    @Test
    void factsMessage_carriesTheRecoveryDeadlineAsContext() {
        RecoveryWindow window = new RecoveryWindow(TODAY.minusDays(7), 1, 20,
                List.of(openItem("Stretch", TODAY.minusDays(7))));

        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, false, 0, 0, 0d, List.of()), today(List.of(), window), WeekSignals.empty()));

        String context = message.substring(0, message.indexOf("SIGNALS"));
        assertThat(context).contains("stops being checkable in 1 days");
    }

    // ---- the signals ----

    @Test
    void factsMessage_comparesThisWeekWithTheOneBefore() {
        WeekSignals signals = new WeekSignals(72, 85, List.of(), List.of(), null, null, 0);

        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, false, 1, 0, 10d, List.of()), today(List.of(), null), signals));

        assertThat(message).contains("72% of the items asked were checked");
        assertThat(message).contains("the seven days before: 85%");
        assertThat(message).contains("Skips are left out of both");
    }

    @Test
    void factsMessage_namesWhatIsSlippingAndWhatIsHolding() {
        WeekSignals signals = new WeekSignals(60, null,
                List.of(new WeekSignals.ItemPattern("Reading", 6, 2)),
                List.of(new WeekSignals.ItemPattern("Water", 7, 7)),
                null, null, 0);

        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, false, 1, 0, 10d, List.of()), today(List.of(), null), signals));

        assertThat(message).contains("Slipping: \"Reading\" was left open on 4 of the 6 days");
        assertThat(message).contains("Steady: \"Water\" was checked on all 7 days");
    }

    /** Levels and their trend, and nothing else about the user's mood. */
    @Test
    void factsMessage_carriesTheMoodTrendAsLevels() {
        WeekSignals signals = new WeekSignals(null, null, List.of(), List.of(), 3.6, 3.1, 5);

        String message = DailyBriefingNarrator.factsMessage(facts(
                new YesterdayRecap(YESTERDAY, true, false, 1, 0, 5d, List.of(), 0, 4),
                today(List.of(), null), signals));

        assertThat(message).contains("levels only (1 awful, 5 great)");
        assertThat(message).contains("yesterday 4");
        assertThat(message).contains("this week averaged 3.6 over 5 days logged");
        assertThat(message).contains("the week before 3.1");
    }

    @Test
    void factsMessage_saysWhenNothingStandsOut() {
        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, true, 4, 0, 80d, List.of()), today(List.of(), null), WeekSignals.empty()));

        assertThat(message).contains("Nothing stands out");
    }

    // ---- goals ----

    @Test
    void factsMessage_givesABehindGoalTheDailyPaceItNeeds() {
        GoalAhead behind = goal("Read 12 books", 3d, 12d, "books", 17, 25, 9d, 0.5, 70, GoalPace.BEHIND);

        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, false, 1, 0, 10d, List.of()), today(List.of(behind), null), WeekSignals.empty()));

        assertThat(message).contains("Goal \"Read 12 books\": 25% done (3 of 12 books)");
        assertThat(message).contains("ends in 17 days, behind pace");
        assertThat(message).contains("says 70% by today");
        assertThat(message).contains("takes 0.5 books a day");
    }

    /** The XP for a goal is paid on completion, so a met target is a reward left on the table. */
    @Test
    void factsMessage_pointsAtAReachedGoalThatOnlyNeedsMarking() {
        GoalAhead reached = goal("Run 50 km", 50d, 50d, "km", 6, 100, 0d, null, 80, GoalPace.REACHED);

        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, false, 1, 0, 10d, List.of()), today(List.of(reached), null), WeekSignals.empty()));

        assertThat(message).contains("target reached but not marked as done yet");
        assertThat(message).contains("pays its XP");
    }

    @Test
    void factsMessage_carriesAnOverdueGoalAsOverdue() {
        GoalAhead overdue = goal("Save 1000", 580d, 1000d, "BRL", -4, 58, 420d, null, 100, GoalPace.OVERDUE);

        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, false, 1, 0, 10d, List.of()), today(List.of(overdue), null), WeekSignals.empty()));

        assertThat(message).contains("ended 4 days ago without reaching the target");
        assertThat(message).contains("58% done");
        // Whole numbers read as counts, not as 580.0 of 1000.0.
        assertThat(message).contains("580 of 1000 BRL");
    }

    /**
     * The prose reads goalsAhead, the new list. goalsApproaching is kept for app builds that
     * are already installed and must not leak a second copy of the same goal into the prompt.
     */
    @Test
    void factsMessage_readsGoalsAheadAndNotTheLegacyList() {
        GoalAhead legacyOnly = goal("Legacy", 1d, 2d, "x", 3, 50, 1d, 0.3, 40, GoalPace.ON_TRACK);

        String message = DailyBriefingNarrator.factsMessage(facts(
                recap(true, false, 1, 0, 10d, List.of()),
                new TodayAhead(3, true, 2, 5, List.of(legacyOnly), null, List.of()),
                WeekSignals.empty()));

        assertThat(message).doesNotContain("Legacy");
    }

    // ---- the day ----

    /** The weekday is what lets the model say "on Sunday" instead of a date. */
    @Test
    void dayLabel_namesTheWeekday() {
        assertThat(DailyBriefingNarrator.dayLabel(LocalDate.of(2026, 10, 7)))
                .isEqualTo("Wednesday 2026-10-07");
    }

    // ---- helpers ----

    private static DailyBriefingFactsBuilder.Facts facts(YesterdayRecap yesterday, TodayAhead today,
                                                         WeekSignals signals) {
        return new DailyBriefingFactsBuilder.Facts(yesterday, today, signals);
    }

    private static TodayAhead today(List<GoalAhead> goalsAhead, RecoveryWindow recovery) {
        return new TodayAhead(4, true, 3, 5, List.of(), recovery, goalsAhead);
    }

    private static YesterdayRecap recap(boolean hadRoutine, boolean complete, int done,
                                        int skipped, double xp, List<OpenItem> open) {
        return new YesterdayRecap(YESTERDAY, hadRoutine, complete, done, skipped, xp, open, 0, null);
    }

    private static GoalAhead goal(String name, double current, double target, String unit,
                                  long daysRemaining, int percent, double remaining,
                                  Double perDay, int expected, GoalPace pace) {
        return new GoalAhead(UUID.randomUUID(), name, "icon", current, target, unit,
                TODAY.plusDays(daysRemaining), daysRemaining, percent, remaining, perDay,
                expected, pace);
    }

    private static OpenItem openItem(String name, LocalDate date) {
        return new OpenItem(UUID.randomUUID(), UUID.randomUUID(), date, UUID.randomUUID(),
                "Morning", SnapshotItemType.HABIT, name, "icon", "Warm-up", 8d);
    }
}
