package beyou.beyouapp.backend.domain.secondbrain;

/**
 * Where a profile fact came from. Mirrored by {@code second_brain_facts_origin_check}, last
 * widened in V47. Adding a value here without adding it there makes every write of the new
 * origin fail.
 */
public enum SecondBrainFactOrigin {
    /** Typed by the person on the page. */
    USER,
    /** Learned from an interview answer. */
    ANSWER,
    /** Saved by the assistant in a chat, which told the person it did. */
    ASSISTANT,
    /** A sentence of the hidden note the assistant kept before step 2, which the person kept. */
    OLD_NOTE
}
