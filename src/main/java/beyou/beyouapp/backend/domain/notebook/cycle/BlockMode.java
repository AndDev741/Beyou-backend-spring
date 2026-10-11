package beyou.beyouapp.backend.domain.notebook.cycle;

/**
 * What a study block asks for.
 *
 * <p>Persisted as a string and mirrored by {@code study_cycle_blocks_mode_check} and
 * {@code study_cycles_default_mode_check} in V39. <b>Adding a value here without adding it there
 * makes every write of the new mode fail</b>, at insert time.
 */
public enum BlockMode {
    /** A number of minutes on the topic: the block is done when they are reached. */
    TIME,
    /** A concept to understand: the block closes when its page is done. */
    GOAL
}
