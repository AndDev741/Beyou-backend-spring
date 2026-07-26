package beyou.beyouapp.backend.domain.feedback.dto;

/**
 * The console's headline numbers (R12) — how many submissions sit in each
 * triage state.
 *
 * Counted in the database, never by measuring a loaded page: the whole point
 * of the number is to describe rows the admin has not fetched. A state with no
 * rows reports zero rather than being absent, so the console never has to
 * distinguish "none" from "missing".
 *
 * Unfiltered by design: these are the inbox tabs, not a summary of whatever
 * the current listing filter happens to be.
 */
public record FeedbackStatusCountsDTO(
        long open,
        long takingCare,
        long closed,
        long total
) {
}
