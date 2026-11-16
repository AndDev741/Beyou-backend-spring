package beyou.beyouapp.backend.domain.secondbrain;

/**
 * What a profile fact is about. Mirrored by {@code second_brain_facts_kind_check} in V45.
 * Adding a value here without adding it there makes every write of the new kind fail.
 */
public enum SecondBrainFactKind {
    /** Something the person is working toward. */
    OBJECTIVE,
    /** Something they enjoy. */
    LIKE,
    /** Their circumstances: who they live with, what they do, how their week is shaped. */
    ABOUT
}
