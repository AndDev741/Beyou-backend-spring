package beyou.beyouapp.backend.domain.feedback;

/**
 * R11/KD4 — triage state of a submission. This is an internal tool for the
 * admin: it is never returned to the submitting user, because only a written
 * reply speaks to them. Every submission starts {@link #OPEN}.
 *
 * Persisted as a string; the values are mirrored by a CHECK constraint in
 * {@code V9__feedback.sql}, so adding one here needs a migration too.
 */
public enum FeedbackStatus {
    OPEN,
    TAKING_CARE,
    CLOSED
}
