package beyou.beyouapp.backend.domain.briefing.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One morning's briefing, as the clients read it.
 *
 * <p>Two halves with very different guarantees, and telling them apart is what makes the
 * dialog survive a bad day for the LLM chain. {@link #yesterday()} and {@link #today()} are
 * computed from the database and always answer. {@link #narrative()} is generated prose and
 * may be absent; a client that finds nothing there renders its own translated copy rather
 * than an empty panel.
 *
 * <p>Server-built only, never deserialized from a request body, so the convenience
 * constructors below are safe. See {@code SnapshotCheckResponseDTO} for why request DTOs
 * never get one.
 *
 * @param date     the account's own day this briefing describes
 * @param yesterday what happened on the previous day, and what is still open on it
 * @param today    what is coming, and anything with a deadline attached
 * @param narrative the generated lines, never null as an object — its status says whether
 *                  there is anything in it
 * @param seenAt   when the user last acknowledged this day's dialog, or null if never. The
 *                 clients use this and not their own storage, so closing the dialog on one
 *                 device closes it on the other
 */
public record DailyBriefingResponseDTO(
    LocalDate date,
    YesterdayRecap yesterday,
    TodayAhead today,
    BriefingNarrative narrative,
    Instant seenAt
) {

    /**
     * Whether this briefing is worth interrupting somebody with.
     *
     * <p>The rule lives here rather than in each client because there are two clients and
     * they would drift. A dialog that greets someone every morning with "nothing happened"
     * teaches them to dismiss it unread, and then it is dead on the mornings it matters —
     * so a briefing with nothing open, nothing scheduled, no goal moving and no deadline
     * is not shown at all.
     *
     * <p>Yesterday being complete DOES count as worth showing: finishing a day is the
     * result the whole product is about, and saying so is the one cheerful thing in here.
     * What does not count is an account that simply had no yesterday, which is every
     * account on its first two mornings.
     */
    public boolean worthShowing() {
        boolean somethingAboutYesterday = yesterday != null
            && (yesterday.hadRoutine() || yesterday.moodLevel() != null);
        boolean somethingAboutToday = today != null
            && (today.scheduledItemCount() > 0
                || !today.goalsApproaching().isEmpty()
                || today.recovery() != null);
        return somethingAboutYesterday || somethingAboutToday;
    }
}
