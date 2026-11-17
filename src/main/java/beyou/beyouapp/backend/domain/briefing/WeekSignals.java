package beyou.beyouapp.backend.domain.briefing;

import java.util.List;

/**
 * What the last two weeks say, computed for the narrator and never sent to a client.
 *
 * <p>This exists because the first version of the prompt had nothing to say. It was handed
 * the same counts the panel renders, told never to state a number it was not given, and so
 * every morning it wrote the panel back out as prose: "you have 25 items today and a 93-day
 * streak", directly under the line that already said so. An insight needs something the
 * screen does not already show, and the cheapest honest source of that is the snapshot rows
 * the builder was already reading, two weeks of them instead of one.
 *
 * <p>Kept off the response DTO on purpose. These numbers are inputs to the phrasing, not
 * figures a client renders; putting them on the wire would make them a contract both
 * clients have to keep in step with for no screen that reads them.
 *
 * <p><b>Mood levels only.</b> The journal text never reaches this record, and so never
 * reaches the model. Same rule as the agent's {@code getUserMoodHistory} tool: what a person
 * wrote about their day is theirs, and a morning summary is not a reason to read it.
 *
 * @param thisWeekPercent     share of the items asked of the user over the seven days ending
 *                            yesterday that were checked, skips left out of both sides because
 *                            a skip is an answer and not a miss. Null when nothing was asked
 * @param previousWeekPercent the same for the seven days before those, null likewise
 * @param slipping            items not done on most of the times they came up this week, a skip
 *                            counting as not done. One skip is a choice; the same item skipped
 *                            again and again is a habit being dropped, and this list is where
 *                            that shows
 * @param steady              items done every time they came up this week, with no skip
 * @param moodAverage         mean of the levels logged over the seven days ending yesterday,
 *                            one decimal, null when none were
 * @param previousMoodAverage the same for the week before, null likewise
 * @param moodDaysLogged      how many of those seven days carry a level
 */
public record WeekSignals(
    Integer thisWeekPercent,
    Integer previousWeekPercent,
    List<ItemPattern> slipping,
    List<ItemPattern> steady,
    Double moodAverage,
    Double previousMoodAverage,
    int moodDaysLogged
) {

    /** Nothing in the window at all, which is every account's first week. */
    public static WeekSignals empty() {
        return new WeekSignals(null, null, List.of(), List.of(), null, null, 0);
    }

    /**
     * One routine item's week, counted per time it came up: an item in two routines, or twice
     * in one, counts twice on the same day.
     *
     * @param name     what the user calls it, from the snapshot so a renamed habit reads the
     *                 way it did on the days being described
     * @param asked    times it came up, skipped ones included
     * @param checked  times it was checked, however late
     * @param skipped  times it was skipped
     */
    public record ItemPattern(String name, int asked, int checked, int skipped) {
        /** Times it was not done, skipped or left open. */
        public int missed() {
            return asked - checked;
        }

        /** Times it was neither checked nor skipped. */
        public int leftOpen() {
            return asked - checked - skipped;
        }
    }
}
