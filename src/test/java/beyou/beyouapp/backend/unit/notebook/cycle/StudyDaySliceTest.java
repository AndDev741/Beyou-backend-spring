package beyou.beyouapp.backend.unit.notebook.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleBlock;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyDaySlice;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyDaySlice.Entry;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyLap;

/** What fits in what is left of a day, and when each part of it starts. */
class StudyDaySliceTest {

    private static final UUID CYCLE = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-11T12:00:00Z");

    private final List<StudyCycleBlock> blocks = new ArrayList<>();

    /** Each open block takes what it still lacks, until the day runs out. */
    @Test
    void theSliceFillsTheDaysBudgetFromTheFirstOpenBlock() {
        StudyCycleBlock a1 = block(A, 70);
        StudyCycleBlock b1 = block(B, 70);
        block(A, 70);
        StudyLap lap = lap();
        lap.credit(A, 20, AT, b -> null);

        List<Entry> slice = StudyDaySlice.slice(lap, 100, null, 0);

        assertThat(slice).extracting(e -> e.block()).containsExactly(a1, b1);
        assertThat(slice).extracting(Entry::minutesToday).containsExactly(50, 50);
        assertThat(slice.get(0).credited()).isEqualTo(20);
        assertThat(slice.get(0).start()).as("no start time, no clock").isNull();
    }

    @Test
    void closedBlocksAreSkipped() {
        StudyCycleBlock a1 = block(A, 30);
        StudyCycleBlock b1 = block(B, 30);
        StudyLap lap = lap();
        lap.credit(A, 30, AT, b -> null);

        List<Entry> slice = StudyDaySlice.slice(lap, 30, null, 0);

        assertThat(slice).extracting(e -> e.block()).containsExactly(b1).doesNotContain(a1);
    }

    /** An hour already studied at 19:00 puts the next block at 20:00, not again at 19:00. */
    @Test
    void whatWasStudiedTodayPushesTheClock() {
        block(A, 40);
        block(B, 70);
        StudyLap lap = lap();

        List<Entry> slice = StudyDaySlice.slice(lap, 120 - 60, LocalTime.of(19, 0), 60);

        assertThat(slice.get(0).start()).isEqualTo(LocalTime.of(20, 0));
        assertThat(slice.get(0).end()).isEqualTo(LocalTime.of(20, 40));
        assertThat(slice.get(1).start()).isEqualTo(LocalTime.of(20, 40));
        assertThat(slice.get(1).minutesToday()).isEqualTo(20);
        assertThat(slice.get(1).end()).isEqualTo(LocalTime.of(21, 0));
    }

    /** The lap ends inside the day: the next lap's blocks follow from the start, flagged, once. */
    @Test
    void aLapThatEndsInsideTheDayWrapsIntoTheNextOnce() {
        StudyCycleBlock a1 = block(A, 30);
        StudyCycleBlock b1 = block(B, 30);
        StudyLap lap = lap();
        lap.credit(A, 30, AT, b -> null);

        List<Entry> slice = StudyDaySlice.slice(lap, 600, null, 0);

        assertThat(slice).extracting(e -> e.block()).containsExactly(b1, a1, b1);
        assertThat(slice).extracting(Entry::nextLap).containsExactly(false, true, true);
        assertThat(slice.get(1).credited()).as("the next lap has no progress yet").isZero();
        assertThat(slice.get(1).minutesToday()).isEqualTo(30);
    }

    @Test
    void aRestDayHasNoSlice() {
        block(A, 30);

        assertThat(StudyDaySlice.slice(lap(), 0, LocalTime.of(19, 0), 0)).isEmpty();
    }

    @Test
    void aDayAlreadyStudiedOutHasNoSlice() {
        block(A, 30);

        assertThat(StudyDaySlice.slice(lap(), -15, LocalTime.of(19, 0), 135)).isEmpty();
    }

    private StudyLap lap() {
        return new StudyLap(CYCLE, 1, blocks, List.of());
    }

    private StudyCycleBlock block(UUID topic, int minutes) {
        StudyCycleBlock block = new StudyCycleBlock();
        block.setId(UUID.randomUUID());
        block.setCycleId(CYCLE);
        block.setTopicId(topic);
        block.setMode(BlockMode.TIME);
        block.setPlannedMinutes(minutes);
        block.setPosition(blocks.size() + 1);
        blocks.add(block);
        return block;
    }
}
