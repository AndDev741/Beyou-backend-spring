package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * One lap of one cycle, in memory: the blocks in queue order and their progress rows. Every rule
 * about how minutes and understanding move a lap lives here, and nothing here touches the
 * database. {@link StudyCycleService} loads the rows, asks this class, and saves what changed.
 * That split is what lets the walk be tested block by block without a Postgres behind it.
 *
 * <p>A block with no row is OPEN with no minutes. {@link #row} creates the row the first time a
 * rule needs to write to it, and {@link #created()} hands the new ones back to be saved.
 */
public final class StudyLap {

    /**
     * What a GOAL block is about to study: the concept it resolves to now, or null when there is
     * nothing to understand (the roadmap is done, or the chosen page already is). Supplied by the
     * service, which reads the notebook; a block with a null target behaves like a TIME block.
     */
    @FunctionalInterface
    public interface Targets {
        UUID resolve(StudyCycleBlock block);
    }

    /** The block a credit landed on first, for the response that tells the person where it went. */
    public record Credit(UUID blockId, int position, int minutesBefore, int minutesAfter, int plannedMinutes,
            LapBlockState state) {
    }

    private final UUID cycleId;
    private final int lapNumber;
    private final List<StudyCycleBlock> blocks;
    private final Map<UUID, StudyCycleLapBlock> rows = new HashMap<>();
    private final List<StudyCycleLapBlock> created = new ArrayList<>();

    /**
     * @param blocks the cycle's blocks in position order
     * @param rows   the rows of THIS lap; rows of other laps are ignored
     */
    public StudyLap(UUID cycleId, int lapNumber, List<StudyCycleBlock> blocks, Collection<StudyCycleLapBlock> rows) {
        this.cycleId = cycleId;
        this.lapNumber = lapNumber;
        this.blocks = blocks;
        for (StudyCycleLapBlock row : rows) {
            if (row.getLapNumber() == lapNumber) {
                this.rows.put(row.getBlockId(), row);
            }
        }
    }

    public int lapNumber() {
        return lapNumber;
    }

    public List<StudyCycleBlock> blocks() {
        return blocks;
    }

    /** The rows this lap created and nobody has saved yet. */
    public List<StudyCycleLapBlock> created() {
        return created;
    }

    public StudyCycleLapBlock rowOrNull(StudyCycleBlock block) {
        return rows.get(block.getId());
    }

    /** The block's row in this lap, created OPEN with no minutes when it has none. */
    public StudyCycleLapBlock row(StudyCycleBlock block) {
        StudyCycleLapBlock row = rows.get(block.getId());
        if (row == null) {
            row = new StudyCycleLapBlock(cycleId, block.getId(), lapNumber);
            rows.put(block.getId(), row);
            created.add(row);
        }
        return row;
    }

    public LapBlockState state(StudyCycleBlock block) {
        StudyCycleLapBlock row = rows.get(block.getId());
        return row == null ? LapBlockState.OPEN : row.getState();
    }

    public int minutes(StudyCycleBlock block) {
        StudyCycleLapBlock row = rows.get(block.getId());
        return row == null ? 0 : row.getMinutes();
    }

    /**
     * The page a GOAL block is known to aim at without asking the roadmap: its stored target, or
     * the chosen page of a PAGE block. Null for a NEXT block nobody has resolved yet.
     */
    public UUID knownTarget(StudyCycleBlock block) {
        if (!block.goal()) return null;
        StudyCycleLapBlock row = rows.get(block.getId());
        if (row != null && row.getTargetPageId() != null) return row.getTargetPageId();
        return block.getGoalSource() == GoalSource.PAGE ? block.getPageId() : null;
    }

    // ------------------------------------------------------------------ credit

    /**
     * Minutes studied on a topic, walked through that topic's blocks.
     *
     * <ol>
     *   <li>Its OPEN blocks in queue order each take what they still lack, up to what is left.</li>
     *   <li>A block that reaches its planned minutes closes: DONE for a TIME block (or a GOAL block
     *       with nothing to understand), TURN_USED for a GOAL block whose concept is still open.
     *       The cycle moves on, and the topic's next block resolves to that same concept because
     *       it is still not done.</li>
     *   <li>Whatever is left after the topic's last open block goes onto the topic's last block
     *       ("80 of 70 min"), so no studied minute disappears.</li>
     * </ol>
     *
     * <p>Out-of-order study is fine: the minutes go to this topic's first open block wherever it
     * sits in the queue.
     *
     * @return the first block that took minutes, or null when the topic has no block in this cycle
     */
    public Credit credit(UUID topicId, int minutes, Instant at, Targets targets) {
        List<StudyCycleBlock> topicBlocks = blocksOf(topicId);
        if (topicBlocks.isEmpty() || minutes <= 0) return null;

        int remaining = minutes;
        Credit first = null;
        for (StudyCycleBlock block : topicBlocks) {
            if (remaining <= 0) break;
            if (state(block).closed()) continue;
            StudyCycleLapBlock row = row(block);
            resolve(block, row, targets);
            int before = row.getMinutes();
            int take = Math.max(0, Math.min(remaining, block.getPlannedMinutes() - before));
            row.setMinutes(before + take);
            remaining -= take;
            closeIfFull(block, row, at);
            if (take > 0 && first == null) {
                first = new Credit(block.getId(), block.getPosition(), before, row.getMinutes(),
                        block.getPlannedMinutes(), row.getState());
            }
        }
        if (remaining > 0) {
            StudyCycleBlock last = topicBlocks.get(topicBlocks.size() - 1);
            StudyCycleLapBlock row = row(last);
            int before = row.getMinutes();
            row.setMinutes(before + remaining);
            if (first == null) {
                first = new Credit(last.getId(), last.getPosition(), before, row.getMinutes(),
                        last.getPlannedMinutes(), row.getState());
            }
        }
        return first;
    }

    /**
     * Takes back what a deleted session put in: from the topic's blocks in reverse queue order,
     * only blocks that hold minutes. A DONE or TURN_USED block that falls below its planned
     * minutes opens again; an UNDERSTOOD block stays closed, because the concept was understood
     * whatever the minutes say.
     */
    public void debit(UUID topicId, int minutes) {
        List<StudyCycleBlock> topicBlocks = blocksOf(topicId);
        int remaining = minutes;
        for (int i = topicBlocks.size() - 1; i >= 0 && remaining > 0; i--) {
            StudyCycleBlock block = topicBlocks.get(i);
            StudyCycleLapBlock row = rows.get(block.getId());
            if (row == null || row.getMinutes() <= 0) continue;
            int take = Math.min(remaining, row.getMinutes());
            row.setMinutes(row.getMinutes() - take);
            remaining -= take;
            LapBlockState state = row.getState();
            if ((state == LapBlockState.DONE || state == LapBlockState.TURN_USED)
                    && row.getMinutes() < block.getPlannedMinutes()) {
                row.setState(LapBlockState.OPEN);
                row.setClosedAt(null);
            }
        }
    }

    /**
     * Closes every OPEN block that already holds its planned minutes, which only happens when the
     * person lowered them. Run on save, so the block does not sit open with "80 of 60" until the
     * next credit happens to walk past it.
     *
     * @return whether anything closed
     */
    public boolean closeFull(Instant at, Targets targets) {
        boolean changed = false;
        for (StudyCycleBlock block : blocks) {
            StudyCycleLapBlock row = rows.get(block.getId());
            if (row == null || row.getState().closed() || row.getMinutes() < block.getPlannedMinutes()) continue;
            resolve(block, row, targets);
            closeIfFull(block, row, at);
            changed = true;
        }
        return changed;
    }

    // ----------------------------------------------------------- understanding

    /**
     * Pages reached DONE: every OPEN GOAL block aiming at one of them is UNDERSTOOD, unless it
     * also asks for the page's quiz and the quiz has not been passed. Then it stays open ("page
     * done, quiz missing") until {@link #quizPassed} closes it.
     *
     * <p>A block that understands early closes at once and the cycle moves on: understanding is
     * rewarded, sitting is not. Nothing here ever reopens a closed block.
     *
     * @return whether anything closed
     */
    public boolean pagesDone(Set<UUID> pageIds, Instant at, Predicate<UUID> quizPassed) {
        boolean changed = false;
        for (StudyCycleBlock block : blocks) {
            if (!block.goal() || state(block).closed()) continue;
            UUID target = knownTarget(block);
            if (target == null || !pageIds.contains(target)) continue;
            if (block.isRequireQuiz() && !quizPassed.test(target)) continue;
            understood(block, target, at);
            changed = true;
        }
        return changed;
    }

    /**
     * A quiz on {@code pageId} was passed and the page is already DONE: the OPEN GOAL blocks that
     * aim at it and were waiting for the quiz are UNDERSTOOD.
     */
    public boolean quizPassed(UUID pageId, Instant at) {
        boolean changed = false;
        for (StudyCycleBlock block : blocks) {
            if (!block.goal() || !block.isRequireQuiz() || state(block).closed()) continue;
            if (!pageId.equals(knownTarget(block))) continue;
            understood(block, pageId, at);
            changed = true;
        }
        return changed;
    }

    // -------------------------------------------------------------------- lap

    /** Every block is closed. A cycle with no blocks never completes a lap. */
    public boolean complete() {
        if (blocks.isEmpty()) return false;
        for (StudyCycleBlock block : blocks) {
            if (!state(block).closed()) return false;
        }
        return true;
    }

    /** The lap's numbers as they stand. */
    public Totals totals() {
        int minutes = 0;
        int planned = 0;
        int closed = 0;
        int understood = 0;
        int turnsUsed = 0;
        for (StudyCycleBlock block : blocks) {
            planned += block.getPlannedMinutes();
            minutes += minutes(block);
            LapBlockState state = state(block);
            if (state.closed()) closed++;
            if (state == LapBlockState.UNDERSTOOD) understood++;
            if (state == LapBlockState.TURN_USED) turnsUsed++;
        }
        return new Totals(minutes, planned, closed, blocks.size(), understood, turnsUsed);
    }

    public record Totals(int minutes, int plannedMinutes, int blocksClosed, int blocks, int understood,
            int turnsUsed) {
    }

    /** The first OPEN block, or null when the lap has none. */
    public StudyCycleBlock firstOpen() {
        for (StudyCycleBlock block : blocks) {
            if (!state(block).closed()) return block;
        }
        return null;
    }

    // --------------------------------------------------------------- internals

    public List<StudyCycleBlock> blocksOf(UUID topicId) {
        List<StudyCycleBlock> out = new ArrayList<>();
        for (StudyCycleBlock block : blocks) {
            if (block.getTopicId().equals(topicId)) out.add(block);
        }
        return out;
    }

    /** Fixes the concept of a GOAL block the first time it is worked on. */
    private static void resolve(StudyCycleBlock block, StudyCycleLapBlock row, Targets targets) {
        if (block.goal() && row.getTargetPageId() == null) {
            row.setTargetPageId(targets.resolve(block));
        }
    }

    private static void closeIfFull(StudyCycleBlock block, StudyCycleLapBlock row, Instant at) {
        if (row.getState().closed() || row.getMinutes() < block.getPlannedMinutes()) return;
        boolean conceptOpen = block.goal() && row.getTargetPageId() != null;
        row.setState(conceptOpen ? LapBlockState.TURN_USED : LapBlockState.DONE);
        row.setClosedAt(at);
    }

    private void understood(StudyCycleBlock block, UUID target, Instant at) {
        StudyCycleLapBlock row = row(block);
        row.setTargetPageId(target);
        row.setState(LapBlockState.UNDERSTOOD);
        row.setClosedAt(at);
    }
}
