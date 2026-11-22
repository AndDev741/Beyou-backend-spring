package beyou.beyouapp.backend.domain.secondbrain;

import java.util.UUID;

/**
 * A note an answer may leave on one of the person's things, before its owner is checked and
 * the per-thing cap applied.
 */
public record LearnedNote(SecondBrainSubjectType type, UUID id, String text) {}
