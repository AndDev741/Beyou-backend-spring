package beyou.beyouapp.backend.domain.secondbrain;

import java.util.Locale;

/**
 * The Beyou data Second Brain may read, one switch each.
 *
 * <p>Mirrored by {@code second_brain_sources_source_check} in V45. Adding a value here without
 * adding it there makes every write of the new source fail at insert time.
 *
 * <p>There is no JOURNAL source on purpose. The mood journal text is never read by this
 * feature; {@link #MOOD} covers the levels only.
 */
public enum SecondBrainSource {

    /** Routine snapshots: the routine and areas blocks, and the week's patterns for the model. */
    ROUTINES(true),
    /** Open goals and their pace. */
    GOALS(true),
    /** Mood levels, as two weekly averages. Never the journal. */
    MOOD(true),
    /** Open calendar tasks: the next three weeks and the overdue ones, title and date. */
    CALENDAR(false),
    /** Study topic titles with their progress. Never page content. */
    NOTEBOOK(false),
    /** The most recent active notes on the Ideas board. */
    IDEAS(false);

    private final boolean enabledByDefault;

    SecondBrainSource(boolean enabledByDefault) {
        this.enabledByDefault = enabledByDefault;
    }

    /** What an account with no row for this source gets. */
    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    /**
     * The source a path segment names. An unknown one raises {@link IllegalArgumentException},
     * which the global handler already answers as 400 {@code INVALID_REQUEST}.
     */
    public static SecondBrainSource fromPath(String raw) {
        try {
            return valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown Second Brain source");
        }
    }
}
