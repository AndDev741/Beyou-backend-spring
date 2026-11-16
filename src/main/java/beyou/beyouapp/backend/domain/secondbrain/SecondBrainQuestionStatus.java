package beyou.beyouapp.backend.domain.secondbrain;

/**
 * Where a question stands. Mirrored by {@code second_brain_questions_status_check} in V45.
 * Adding a value here without adding it there makes every write of the new status fail.
 */
public enum SecondBrainQuestionStatus {
    OPEN,
    ANSWERED,
    SKIPPED
}
