package beyou.beyouapp.backend.domain.briefing;

/**
 * Where the generated half of a briefing stands.
 *
 * <p>Stored as the name in {@code daily_briefing.narrative_status}, which carries a CHECK
 * constraint listing exactly these three. Adding a value here without a migration alongside
 * it makes every write of the new kind fail at insert time, inside a request nobody is
 * watching.
 *
 * <p>None of these is an error. The facts half of a briefing is computed from the database
 * and always answers; the prose is the optional part, and a client that finds no prose
 * renders translated fallback copy instead of an empty panel. That is why
 * {@code GET /daily-briefing} never raises {@code AI_UNAVAILABLE} the way
 * {@code POST /onboarding/suggestions} does — there, the suggestions ARE the response.
 */
public enum NarrativeStatus {

    /**
     * Asked for, and the deadline passed before the model answered.
     *
     * <p>The call is not cancelled: it keeps running on the narration pool and writes the
     * row if it lands, so the next open of the same day usually finds {@code READY}. This
     * is the ordinary outcome of a slow free-tier provider, not a failure.
     */
    PENDING,

    /** {@code narrative_json} holds the generated lines. */
    READY,

    /**
     * The chain refused or failed twice.
     *
     * <p>Not retried for the rest of that day. A chain in cooldown refuses the retry too,
     * and the panel reads identically with or without the prose, so a retry spends a
     * request to change nothing the user can see.
     */
    UNAVAILABLE
}
