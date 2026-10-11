package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The part of the queue that fits in what is left of a day. Pure, like {@link StudyLap}.
 *
 * <p>Starting at the lap's first OPEN block, each OPEN block takes what it still lacks, up to what
 * the day has left. When the lap runs out inside the day the slice carries on with the next lap's
 * blocks from the start, with no progress yet, and wraps once at most: a person with more hours
 * than two laps does not need a longer list, they need a bigger cycle.
 *
 * <p>Clock times only when the day has a start time. The cursor starts at the start time plus
 * what was already studied today, so a person who did an hour at 19:00 sees the next block at
 * 20:00 and not again at 19:00.
 */
public final class StudyDaySlice {

    private StudyDaySlice() {
    }

    /**
     * One block in today's slice.
     *
     * @param credited     minutes the block already holds in its lap (0 for the next lap)
     * @param minutesToday its share of what is left of today
     * @param nextLap      true when the block belongs to the lap after this one
     * @param start        the clock time it starts at, or null without a start time
     */
    public record Entry(StudyCycleBlock block, int credited, int minutesToday, boolean nextLap,
            LocalTime start, LocalTime end) {
    }

    /**
     * @param budget      minutes left today: the day's minutes less what was studied today, not
     *                    below zero
     * @param startTime   the day's start time, or null
     * @param studiedToday minutes already studied today, which push the clock forward
     */
    public static List<Entry> slice(StudyLap lap, int budget, LocalTime startTime, int studiedToday) {
        List<Entry> entries = new ArrayList<>();
        int remaining = Math.max(0, budget);
        LocalTime cursor = startTime == null ? null : startTime.plusMinutes(studiedToday);

        for (StudyCycleBlock block : lap.blocks()) {
            if (remaining <= 0) break;
            if (lap.state(block).closed()) continue;
            int credited = lap.minutes(block);
            int share = Math.min(block.getPlannedMinutes() - credited, remaining);
            if (share <= 0) continue;
            remaining -= share;
            entries.add(entry(block, credited, share, false, cursor));
            cursor = cursor == null ? null : cursor.plusMinutes(share);
        }

        if (remaining > 0 && !lap.blocks().isEmpty()) {
            for (StudyCycleBlock block : lap.blocks()) {
                if (remaining <= 0) break;
                int share = Math.min(block.getPlannedMinutes(), remaining);
                remaining -= share;
                entries.add(entry(block, 0, share, true, cursor));
                cursor = cursor == null ? null : cursor.plusMinutes(share);
            }
        }
        return entries;
    }

    private static Entry entry(StudyCycleBlock block, int credited, int share, boolean nextLap, LocalTime cursor) {
        return new Entry(block, credited, share, nextLap, cursor, cursor == null ? null : cursor.plusMinutes(share));
    }
}
