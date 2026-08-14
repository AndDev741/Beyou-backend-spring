package beyou.beyouapp.backend.domain.checkday.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;

/**
 * One owner's day-by-day history over a range — the whole payload of
 * {@code GET /check-history} (R9, KTD23).
 *
 * <p>{@link #from} and {@link #to} are the <em>effective</em> range, not the
 * requested one. A caller asking for more days than the endpoint's cap gets the
 * range clamped rather than an error, and these two fields are how it finds out:
 * a client that renders a strip from its own request parameters instead of from
 * these would silently draw a window it was never sent.
 *
 * <p>{@link #days} carries one entry per day in that range, oldest first, with
 * no gaps. R18 is the reason: a day nobody closed is unknown, not failed, and a
 * response that simply omitted it would leave every client guessing which. The
 * response therefore has a fixed size for a given range, which is also what
 * makes the cap the real bound on the payload.
 */
public record CheckDayResponseDTO(
        CheckDayOwnerType ownerType,
        UUID ownerId,
        /** First day of the effective range, inclusive. Clamped, not echoed. */
        LocalDate from,
        /** Last day of the effective range, inclusive. */
        LocalDate to,
        List<Day> days
) {

    /** One day and how it ended. */
    public record Day(LocalDate day, Outcome outcome) {}

    /**
     * The five stored outcomes plus {@link #UNKNOWN}, which is the wire's word
     * for "no row".
     *
     * <p>Deliberately a separate type from the persisted {@code CheckDayOutcome}
     * rather than a sixth constant on it. That enum is mirrored by the
     * {@code entity_check_day_outcome_check} constraint in
     * {@code V13__check_progress_and_entity_check_day.sql}; adding a value that
     * can never be written would put a value in the database's vocabulary that
     * the database rejects. Unknown is a fact about the response, not about a row.
     */
    public enum Outcome {
        DONE,
        SKIPPED,
        MISSED,
        NOT_SCHEDULED,
        NOT_IN_ROUTINE,
        /** No row stored for this day — neither a success nor a failure (R18). */
        UNKNOWN
    }
}
