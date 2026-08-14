package beyou.beyouapp.backend.domain.checkday;

/**
 * R6 — how one day ended for one entity. Exactly one of these, never a
 * combination and never absent: a day with no row is a day the history has not
 * been closed for yet, not a day that means anything.
 *
 * <p>Persisted as a string and mirrored by the {@code entity_check_day_outcome_check}
 * constraint in {@code V13__check_progress_and_entity_check_day.sql}.
 */
public enum CheckDayOutcome {
    /** Checked off. The only outcome that advances a streak. */
    DONE,
    /** Deliberately skipped by the user. */
    SKIPPED,
    /** Scheduled for the day, in a routine, and left unchecked. */
    MISSED,
    /** In a routine, but that routine does not run on this day. */
    NOT_SCHEDULED,
    /** Belongs to no routine at all on this day, so nothing could have been expected. */
    NOT_IN_ROUTINE
}
