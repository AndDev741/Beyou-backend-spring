package beyou.beyouapp.backend.domain.notebook.cycle;

/**
 * Where a GOAL block's concept comes from. Mirrored by {@code study_cycle_blocks_goal_source_check}
 * in V39: adding a value here without adding it there makes every write of it fail.
 */
public enum GoalSource {
    /**
     * Whatever the topic's roadmap says is next ({@code ProgressGraph.nextLeaf}) when the block
     * is reached. Rolls forward by itself, lap after lap.
     */
    NEXT,
    /** One page the person chose. Once that page is done the block has nothing left to understand. */
    PAGE
}
