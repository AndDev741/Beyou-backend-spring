package beyou.beyouapp.backend.domain.briefing.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.briefing.NarrativeStatus;

/**
 * The generated half of a briefing: a few lines per carousel page, and nothing else.
 *
 * <p><b>The model writes prose and never numbers.</b> Every figure the dialog shows — the
 * percentages, the streak, the days remaining, what a late check pays — is computed in Java
 * and lives in the facts records beside this one. Handing a free-tier model the raw account
 * and asking it to work out which goals are at risk buys wrong arithmetic, invented goals,
 * and a different answer each morning for identical data.
 *
 * <p>Two payoffs. The numbers on screen are always right, because nothing generated touched
 * them. And when the chain is down the panel still works: it degrades to facts plus a
 * translated line, which is why {@code GET /daily-briefing} answers 200 with an empty
 * narrative rather than raising {@code AI_UNAVAILABLE}.
 *
 * @param status       whether there is anything in the lists below, and why not if not
 * @param todayLines   for the "what is coming" page. Empty unless status is READY
 * @param yesterdayLines for the recap page. Empty unless status is READY
 */
public record BriefingNarrative(
    NarrativeStatus status,
    List<String> todayLines,
    List<String> yesterdayLines
) {

    /** Nothing generated, for whatever reason. The clients render their own copy. */
    public static BriefingNarrative absent(NarrativeStatus status) {
        return new BriefingNarrative(status, List.of(), List.of());
    }
}
