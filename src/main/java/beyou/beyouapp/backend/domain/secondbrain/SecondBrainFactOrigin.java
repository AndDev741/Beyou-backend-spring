package beyou.beyouapp.backend.domain.secondbrain;

/**
 * Where a profile fact came from. Mirrored by {@code second_brain_facts_origin_check} in V45.
 * Adding a value here without adding it there makes every write of the new origin fail.
 */
public enum SecondBrainFactOrigin {
    /** Typed by the person on the page. */
    USER,
    /** Learned from an interview answer. */
    ANSWER
}
