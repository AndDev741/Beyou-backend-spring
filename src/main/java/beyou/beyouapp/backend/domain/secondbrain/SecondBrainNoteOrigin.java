package beyou.beyouapp.backend.domain.secondbrain;

/**
 * Who wrote a memory note. Mirrored by {@code second_brain_notes_origin_check} in V47.
 * Adding a value here without adding it there makes every write of the new origin fail.
 *
 * <p>The same names {@link SecondBrainFactOrigin} uses, so the page labels both the same way.
 */
public enum SecondBrainNoteOrigin {
    /** Typed by the person. Nothing writes this yet: in this step the person edits and deletes. */
    USER,
    /** Filed from an answer on the Second Brain page. */
    ANSWER,
    /** Saved by the assistant in a chat, which told the person it did. */
    ASSISTANT
}
