package beyou.beyouapp.backend.domain.notebook.cycle;

/**
 * Where a block stands in one lap.
 *
 * <p>Mirrored by {@code study_cycle_lap_blocks_state_check} in V39. <b>Adding a value here without
 * adding it there makes every write of the new state fail</b>, at insert time, inside whatever
 * request happened to close the block.
 */
public enum LapBlockState {
    /** Still to study in this lap. A block with no lap row is OPEN with no minutes. */
    OPEN,
    /** A TIME block (or a GOAL block with nothing left to understand) that reached its minutes. */
    DONE,
    /** A GOAL block whose concept was understood: its page reached DONE. */
    UNDERSTOOD,
    /**
     * A GOAL block whose minutes ran out before the concept was understood. The cycle moves on and
     * the topic's next block picks the same concept up.
     */
    TURN_USED;

    public boolean closed() {
        return this != OPEN;
    }
}
