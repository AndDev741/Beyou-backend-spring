package beyou.beyouapp.backend.domain.secondbrain;

/** A fact an answer may add to the profile, before the limit and the duplicates are applied. */
public record LearnedFact(SecondBrainFactKind kind, String text) {}
