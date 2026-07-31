package beyou.beyouapp.backend.domain.feedback;

/**
 * R2 — the fixed set a user picks from when submitting feedback.
 * Persisted as a string; the values are mirrored by a CHECK constraint in
 * {@code V9__feedback.sql}, so adding one here needs a migration too.
 */
public enum FeedbackCategory {
    BUG,
    FEATURE_REQUEST,
    OTHER
}
