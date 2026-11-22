package beyou.beyouapp.backend.domain.secondbrain;

/**
 * What a memory note, or a NEW_THING question, is about. Mirrored by
 * {@code second_brain_notes_subject_type_check} in V47, and in part by
 * {@code second_brain_notes_former_type_check} and {@code second_brain_questions_subject_check}.
 * Adding a value here without adding it there makes every write of the new type fail.
 */
public enum SecondBrainSubjectType {
    HABIT,
    GOAL,
    ROUTINE,
    CATEGORY,
    /**
     * The thing is gone and its notes had nowhere to go: a routine, a category, or a habit or
     * goal that had no category left. Such a note keeps its thing's name and points at nothing.
     */
    LOOSE
}
