package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.time.Instant;

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainVerdict;

/**
 * The generated overview.
 *
 * @param text        null when none was written yet
 * @param verdict     what the overview says first, null likewise
 * @param generatedAt null likewise
 * @param stale       true with no overview, or with one written before the account's current
 *                    day. The page rewrites a stale overview on the first visit of the day
 */
public record SecondBrainInsightDTO(String text, SecondBrainVerdict verdict, Instant generatedAt, boolean stale) {

    /** What an account that never refreshed gets. */
    public static SecondBrainInsightDTO none() {
        return new SecondBrainInsightDTO(null, null, null, true);
    }
}
