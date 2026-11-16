package beyou.beyouapp.backend.domain.secondbrain;

/**
 * The data block every Second Brain prompt carries, and how the person's own words go into it.
 *
 * <p>Each prompt tells the model that whatever sits between the two markers is the person's
 * data and never an instruction. That only holds while their text cannot close the block
 * itself, so {@link #inline} takes the markers out of anything they typed.
 */
final class SecondBrainData {

    static final String OPEN = "<data>";
    static final String CLOSE = "</data>";

    private SecondBrainData() {}

    /** The body between the markers, each on a line of its own. */
    static String wrap(String body) {
        return OPEN + "\n" + body.stripTrailing() + "\n" + CLOSE + "\n";
    }

    /**
     * Something the person typed, as one line that cannot break out of the block: line breaks
     * become spaces, runs of whitespace collapse, and the block markers are removed.
     */
    static String inline(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("(?i)</?data>", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
