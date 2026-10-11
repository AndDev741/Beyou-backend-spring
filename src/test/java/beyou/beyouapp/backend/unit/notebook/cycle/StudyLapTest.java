package beyou.beyouapp.backend.unit.notebook.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.GoalSource;
import beyou.beyouapp.backend.domain.notebook.cycle.LapBlockState;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleBlock;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyLap;

/**
 * The rules that move a lap, block by block, with no database: the credit walk, understanding,
 * and taking a deleted session back. Each test names the rule it guards.
 */
class StudyLapTest {

    private static final UUID CYCLE = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID CONCEPT = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-11T19:00:00Z");
    private static final StudyLap.Targets NO_TARGET = block -> null;
    private static final StudyLap.Targets CONCEPT_OPEN = block -> CONCEPT;

    private final List<StudyCycleBlock> blocks = new ArrayList<>();

    // ------------------------------------------------------------ the walk

    @Test
    void minutesGoToTheTopicsFirstOpenBlock() {
        StudyCycleBlock a1 = time(A, 70);
        time(B, 70);
        StudyCycleBlock a2 = time(A, 70);
        StudyLap lap = lap();

        StudyLap.Credit credit = lap.credit(A, 25, AT, NO_TARGET);

        assertThat(lap.minutes(a1)).isEqualTo(25);
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.OPEN);
        assertThat(lap.minutes(a2)).isZero();
        assertThat(credit.blockId()).isEqualTo(a1.getId());
        assertThat(credit.minutesBefore()).isZero();
        assertThat(credit.minutesAfter()).isEqualTo(25);
    }

    /** A block that reaches its minutes closes and the rest spills into the topic's next block. */
    @Test
    void aFullBlockClosesAndTheRestSpillsIntoTheTopicsNextBlock() {
        StudyCycleBlock a1 = time(A, 70);
        StudyCycleBlock b1 = time(B, 70);
        StudyCycleBlock a2 = time(A, 70);
        StudyLap lap = lap();

        lap.credit(A, 100, AT, NO_TARGET);

        assertThat(lap.minutes(a1)).isEqualTo(70);
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.DONE);
        assertThat(lap.rowOrNull(a1).getClosedAt()).isEqualTo(AT);
        assertThat(lap.minutes(b1)).as("another topic's block takes none").isZero();
        assertThat(lap.minutes(a2)).isEqualTo(30);
        assertThat(lap.state(a2)).isEqualTo(LapBlockState.OPEN);
    }

    /** Nothing studied disappears: past the topic's last open block, it goes onto its last block. */
    @Test
    void whatIsLeftAfterTheLastOpenBlockGoesOntoTheTopicsLastBlock() {
        StudyCycleBlock a1 = time(A, 70);
        time(B, 70);
        StudyCycleBlock a2 = time(A, 70);
        StudyLap lap = lap();

        lap.credit(A, 150, AT, NO_TARGET);

        assertThat(lap.minutes(a1)).isEqualTo(70);
        assertThat(lap.minutes(a2)).isEqualTo(80);
        assertThat(lap.state(a2)).isEqualTo(LapBlockState.DONE);
        StudyLap.Credit more = lap.credit(A, 10, AT, NO_TARGET);
        assertThat(lap.minutes(a2)).isEqualTo(90);
        assertThat(more.blockId()).isEqualTo(a2.getId());
    }

    /** Studying a topic out of turn is fine: its own first open block takes the minutes. */
    @Test
    void outOfOrderStudyCreditsThatTopicsBlock() {
        StudyCycleBlock a1 = time(A, 70);
        StudyCycleBlock b1 = time(B, 70);
        StudyLap lap = lap();

        lap.credit(B, 30, AT, NO_TARGET);

        assertThat(lap.minutes(a1)).isZero();
        assertThat(lap.minutes(b1)).isEqualTo(30);
        assertThat(lap.firstOpen()).isEqualTo(a1);
    }

    @Test
    void aTopicWithNoBlockCreditsNothing() {
        time(A, 70);
        StudyLap lap = lap();

        assertThat(lap.credit(B, 30, AT, NO_TARGET)).isNull();
        assertThat(lap.created()).isEmpty();
    }

    @Test
    void theLapIsCompleteOnlyWhenEveryBlockIsClosed() {
        time(A, 30);
        time(B, 30);
        StudyLap lap = lap();

        lap.credit(A, 30, AT, NO_TARGET);
        assertThat(lap.complete()).isFalse();
        lap.credit(B, 30, AT, NO_TARGET);
        assertThat(lap.complete()).isTrue();
        assertThat(lap.totals().minutes()).isEqualTo(60);
        assertThat(lap.totals().blocksClosed()).isEqualTo(2);
    }

    @Test
    void aCycleWithNoBlocksNeverCompletesALap() {
        assertThat(lap().complete()).isFalse();
    }

    // -------------------------------------------------------------- GOAL

    /**
     * A GOAL block whose minutes run out before its concept is understood closes as TURN_USED,
     * and the topic's next block picks up the same concept (it is still not done).
     */
    @Test
    void aGoalBlockOutOfMinutesUsesItsTurnAndTheConceptCarriesOn() {
        StudyCycleBlock a1 = goal(A, 70);
        time(B, 70);
        StudyCycleBlock a2 = goal(A, 70);
        StudyLap lap = lap();

        lap.credit(A, 80, AT, CONCEPT_OPEN);

        assertThat(lap.state(a1)).isEqualTo(LapBlockState.TURN_USED);
        assertThat(lap.rowOrNull(a1).getTargetPageId()).isEqualTo(CONCEPT);
        assertThat(lap.state(a2)).isEqualTo(LapBlockState.OPEN);
        assertThat(lap.rowOrNull(a2).getTargetPageId()).isEqualTo(CONCEPT);
        assertThat(lap.minutes(a2)).isEqualTo(10);
    }

    /** NEXT with nothing left on the roadmap has no concept and closes like a TIME block. */
    @Test
    void aGoalBlockWithNothingToUnderstandClosesAsDone() {
        StudyCycleBlock a1 = goal(A, 30);
        StudyLap lap = lap();

        lap.credit(A, 30, AT, NO_TARGET);

        assertThat(lap.state(a1)).isEqualTo(LapBlockState.DONE);
        assertThat(lap.rowOrNull(a1).getTargetPageId()).isNull();
    }

    /** Understanding early closes the block at once, and the next minutes go to the topic's next block. */
    @Test
    void aConceptUnderstoodEarlyClosesTheBlockAtOnce() {
        StudyCycleBlock a1 = goal(A, 70);
        StudyCycleBlock a2 = time(A, 70);
        StudyLap lap = lap();
        lap.credit(A, 20, AT, CONCEPT_OPEN);

        boolean changed = lap.pagesDone(Set.of(CONCEPT), AT, page -> false);

        assertThat(changed).isTrue();
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.UNDERSTOOD);
        assertThat(lap.minutes(a1)).isEqualTo(20);
        lap.credit(A, 15, AT, CONCEPT_OPEN);
        assertThat(lap.minutes(a1)).isEqualTo(20);
        assertThat(lap.minutes(a2)).isEqualTo(15);
    }

    @Test
    void anotherPageFinishingLeavesTheBlockOpen() {
        StudyCycleBlock a1 = goal(A, 70);
        StudyLap lap = lap();
        lap.credit(A, 20, AT, CONCEPT_OPEN);

        assertThat(lap.pagesDone(Set.of(UUID.randomUUID()), AT, page -> true)).isFalse();
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.OPEN);
    }

    /** A chosen page counts even before any credit fixed it: the block knows what it aims at. */
    @Test
    void aChosenPageClosesItsBlockBeforeAnyCredit() {
        StudyCycleBlock a1 = goal(A, 70);
        a1.setGoalSource(GoalSource.PAGE);
        a1.setPageId(CONCEPT);
        StudyLap lap = lap();

        lap.pagesDone(Set.of(CONCEPT), AT, page -> false);

        assertThat(lap.state(a1)).isEqualTo(LapBlockState.UNDERSTOOD);
        assertThat(lap.rowOrNull(a1).getTargetPageId()).isEqualTo(CONCEPT);
    }

    /** "Page done, quiz missing": the block waits for the quiz, then closes when it passes. */
    @Test
    void aBlockThatAsksForTheQuizWaitsForIt() {
        StudyCycleBlock a1 = goal(A, 70);
        a1.setRequireQuiz(true);
        StudyLap lap = lap();
        lap.credit(A, 20, AT, CONCEPT_OPEN);

        lap.pagesDone(Set.of(CONCEPT), AT, page -> false);
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.OPEN);

        assertThat(lap.quizPassed(CONCEPT, AT)).isTrue();
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.UNDERSTOOD);
    }

    @Test
    void aBlockThatAsksForTheQuizClosesAtOnceWhenItWasAlreadyPassed() {
        StudyCycleBlock a1 = goal(A, 70);
        a1.setRequireQuiz(true);
        StudyLap lap = lap();
        lap.credit(A, 20, AT, CONCEPT_OPEN);

        lap.pagesDone(Set.of(CONCEPT), AT, page -> page.equals(CONCEPT));

        assertThat(lap.state(a1)).isEqualTo(LapBlockState.UNDERSTOOD);
    }

    /** A quiz pass alone does not close a block whose page is not done: the service checks that first. */
    @Test
    void aQuizPassOnlyClosesBlocksThatAskForIt() {
        StudyCycleBlock a1 = goal(A, 70);
        StudyLap lap = lap();
        lap.credit(A, 20, AT, CONCEPT_OPEN);

        assertThat(lap.quizPassed(CONCEPT, AT)).isFalse();
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.OPEN);
    }

    // ------------------------------------------------------------ debit

    /** Deleting a session takes its minutes back from the end and reopens what falls short. */
    @Test
    void aDebitTakesMinutesBackFromTheEndAndReopensWhatFallsShort() {
        StudyCycleBlock a1 = time(A, 70);
        StudyCycleBlock a2 = time(A, 70);
        StudyLap lap = lap();
        lap.credit(A, 100, AT, NO_TARGET);

        lap.debit(A, 50);

        assertThat(lap.minutes(a2)).isZero();
        assertThat(lap.minutes(a1)).isEqualTo(50);
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.OPEN);
        assertThat(lap.rowOrNull(a1).getClosedAt()).isNull();
    }

    @Test
    void aDebitReopensATurnUsedBlock() {
        StudyCycleBlock a1 = goal(A, 70);
        StudyLap lap = lap();
        lap.credit(A, 70, AT, CONCEPT_OPEN);
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.TURN_USED);

        lap.debit(A, 10);

        assertThat(lap.state(a1)).isEqualTo(LapBlockState.OPEN);
    }

    /** Understood is understood: taking minutes back never reopens it. */
    @Test
    void aDebitNeverReopensAnUnderstoodBlock() {
        StudyCycleBlock a1 = goal(A, 70);
        StudyLap lap = lap();
        lap.credit(A, 40, AT, CONCEPT_OPEN);
        lap.pagesDone(Set.of(CONCEPT), AT, page -> false);

        lap.debit(A, 40);

        assertThat(lap.minutes(a1)).isZero();
        assertThat(lap.state(a1)).isEqualTo(LapBlockState.UNDERSTOOD);
    }

    /** Lowering a block's minutes below what it holds closes it on save. */
    @Test
    void closeFullClosesABlockWhosePlannedMinutesWereLowered() {
        StudyCycleBlock a1 = time(A, 70);
        StudyLap lap = lap();
        lap.credit(A, 50, AT, NO_TARGET);

        a1.setPlannedMinutes(45);
        assertThat(lap.closeFull(AT, NO_TARGET)).isTrue();

        assertThat(lap.state(a1)).isEqualTo(LapBlockState.DONE);
    }

    // ------------------------------------------------------------ helpers

    private StudyLap lap() {
        return new StudyLap(CYCLE, 1, blocks, List.of());
    }

    private StudyCycleBlock time(UUID topic, int minutes) {
        return block(topic, minutes, BlockMode.TIME);
    }

    private StudyCycleBlock goal(UUID topic, int minutes) {
        StudyCycleBlock block = block(topic, minutes, BlockMode.GOAL);
        block.setGoalSource(GoalSource.NEXT);
        return block;
    }

    private StudyCycleBlock block(UUID topic, int minutes, BlockMode mode) {
        StudyCycleBlock block = new StudyCycleBlock();
        block.setId(UUID.randomUUID());
        block.setCycleId(CYCLE);
        block.setTopicId(topic);
        block.setMode(mode);
        block.setPlannedMinutes(minutes);
        block.setPosition(blocks.size() + 1);
        blocks.add(block);
        return block;
    }
}
