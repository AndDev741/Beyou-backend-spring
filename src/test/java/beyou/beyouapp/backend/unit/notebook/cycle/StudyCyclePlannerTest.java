package beyou.beyouapp.backend.unit.notebook.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.cycle.StudyCyclePlanner;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCyclePlanner.BlockPlan;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCyclePlanner.Plan;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCyclePlanner.TopicInput;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCyclePlanner.TopicPlan;

/**
 * The planner's arithmetic. The first test is the design canvas's own example (Montar.dc.html):
 * if the server and the canvas ever disagree on it, the editor's preview and the saved cycle do.
 */
class StudyCyclePlannerTest {

    private static final UUID CONST = UUID.randomUUID();
    private static final UUID PORT = UUID.randomUUID();
    private static final UUID ADM = UUID.randomUUID();
    private static final UUID RL = UUID.randomUUID();
    private static final UUID TRAB = UUID.randomUUID();
    private static final UUID INFO = UUID.randomUUID();

    /** 180+180+180+180+120+240+0 minutes, six topics, blocks of at most 90. */
    @Test
    void theCanvasExampleComesOutTheSame() {
        Plan plan = StudyCyclePlanner.plan(1080, 90, List.of(
                new TopicInput(CONST, 5, 2, null),
                new TopicInput(PORT, 5, 3, null),
                new TopicInput(ADM, 4, 2, null),
                new TopicInput(RL, 3, 2, null),
                new TopicInput(TRAB, 4, 3, null),
                new TopicInput(INFO, 2, 4, null)));

        assertThat(plan.topics()).extracting(TopicPlan::weight).containsExactly(20, 15, 16, 12, 12, 4);
        assertThat(plan.topics()).extracting(TopicPlan::suggestedMinutes).containsExactly(275, 205, 220, 165, 165, 55);
        assertThat(plan.topics().get(0).blocks()).containsExactly(70, 70, 70, 65);
        assertThat(plan.topics().get(2).blocks()).containsExactly(75, 75, 70);
        assertThat(plan.topics().get(3).blocks()).containsExactly(85, 80);
        assertThat(plan.plannedMinutes()).isEqualTo(1085);
        assertThat(plan.blocks()).hasSize(15);
        assertThat(plan.lapDays()).isEqualTo(7);
        assertThat(plan.topics().get(0).share()).isEqualTo(20d / 79d);
    }

    /**
     * The interleave: heaviest first, round r takes every topic's r-th block, ties in the order
     * given. Every subject comes back every few blocks instead of one eating the week.
     */
    @Test
    void roundsInterleaveTheTopicsHeaviestFirst() {
        Plan plan = StudyCyclePlanner.plan(1080, 90, List.of(
                new TopicInput(CONST, 5, 2, null),
                new TopicInput(PORT, 5, 3, null),
                new TopicInput(ADM, 4, 2, null),
                new TopicInput(RL, 3, 2, null),
                new TopicInput(TRAB, 4, 3, null),
                new TopicInput(INFO, 2, 4, null)));

        assertThat(plan.blocks()).extracting(BlockPlan::topicId).containsExactly(
                CONST, ADM, PORT, RL, TRAB, INFO,
                CONST, ADM, PORT, RL, TRAB,
                CONST, ADM, PORT,
                CONST);
        assertThat(plan.blocks()).extracting(BlockPlan::position)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        assertThat(plan.blocks().get(5).plannedMinutes()).isEqualTo(55);
    }

    @Test
    void anOverrideWinsAndTheSuggestionIsStillReported() {
        Plan plan = StudyCyclePlanner.plan(600, 60, List.of(
                new TopicInput(CONST, 5, 1, 120),
                new TopicInput(PORT, 1, 5, null)));

        TopicPlan overridden = plan.topics().get(0);
        assertThat(overridden.minutes()).isEqualTo(120);
        assertThat(overridden.suggestedMinutes()).isEqualTo(575);
        assertThat(overridden.blocks()).containsExactly(60, 60);
        assertThat(plan.plannedMinutes()).isEqualTo(120 + plan.topics().get(1).minutes());
    }

    /** No minutes in the week: every topic still gets its 5-minute floor, and no lap length. */
    @Test
    void aZeroWeekPlansTheFloorAndNoLapLength() {
        Plan plan = StudyCyclePlanner.plan(0, 90, List.of(
                new TopicInput(CONST, 5, 1, null),
                new TopicInput(PORT, 1, 5, null)));

        assertThat(plan.topics()).extracting(TopicPlan::suggestedMinutes).containsExactly(5, 5);
        assertThat(plan.blocks()).extracting(BlockPlan::plannedMinutes).containsExactly(5, 5);
        assertThat(plan.lapDays()).isNull();
    }

    /** A tiny topic in a big week still gets 5 minutes, never 0, so it never drops out of the cycle. */
    @Test
    void aSmallShareNeverRoundsToZero() {
        Plan plan = StudyCyclePlanner.plan(60, 90, List.of(
                new TopicInput(CONST, 5, 1, null),
                new TopicInput(PORT, 1, 5, null)));

        assertThat(plan.topics().get(1).suggestedMinutes()).isEqualTo(5);
    }

    /**
     * Blocks are as even as 5-minute steps allow: the first blocks take the extra 5s, and a
     * remainder under 5 joins the first block.
     */
    @Test
    void blocksAreEvenInFiveMinuteSteps() {
        assertThat(StudyCyclePlanner.plan(0, 90, List.of(new TopicInput(CONST, 3, 3, 180)))
                .topics().get(0).blocks()).containsExactly(90, 90);
        assertThat(StudyCyclePlanner.plan(0, 90, List.of(new TopicInput(CONST, 3, 3, 179)))
                .topics().get(0).blocks()).containsExactly(94, 85);
        assertThat(StudyCyclePlanner.plan(0, 60, List.of(new TopicInput(CONST, 3, 3, 130)))
                .topics().get(0).blocks()).containsExactly(45, 45, 40);
        assertThat(StudyCyclePlanner.plan(0, 90, List.of(new TopicInput(CONST, 3, 3, 7)))
                .topics().get(0).blocks()).containsExactly(7);
    }

    /** A lap shorter than a day still takes the day it is studied on. */
    @Test
    void aLapShorterThanADayIsOneDay() {
        Plan plan = StudyCyclePlanner.plan(7 * 600, 90, List.of(new TopicInput(CONST, 1, 5, 30)));

        assertThat(plan.lapDays()).isEqualTo(1);
    }

    @Test
    void noTopicsPlansNothing() {
        Plan plan = StudyCyclePlanner.plan(600, 90, List.of());

        assertThat(plan.blocks()).isEmpty();
        assertThat(plan.plannedMinutes()).isZero();
    }
}
