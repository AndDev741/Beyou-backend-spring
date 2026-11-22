package beyou.beyouapp.backend.domain.secondbrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The hidden note the assistant kept about a person before step 2 ({@code users.user_context},
 * up to 2000 characters the model wrote for itself), cut into the sentences the page shows.
 *
 * <p>One function does the cutting, and both the page and the keep and discard routes call it.
 * The page sends back a sentence it was given, and the route finds it by splitting the note
 * the same way, so the two cannot disagree about what a sentence is. Splitting what is left
 * after a sentence goes gives the same sentences again, so a second keep finds its line too.
 */
public final class OldNote {

    /** A sentence becomes a profile line when kept, so it is cut to what a line holds. */
    public static final int LINE_MAX = SecondBrainFact.TEXT_MAX;

    private OldNote() {}

    /**
     * The note's sentences in order: split on line breaks, then after a full stop, an
     * exclamation or a question mark followed by whitespace; each trimmed, empty ones dropped,
     * and each cut to {@link #LINE_MAX} characters at a word boundary.
     *
     * @return empty for a null or blank note
     */
    public static List<String> lines(String note) {
        List<String> lines = new ArrayList<>();
        if (note == null || note.isBlank()) {
            return lines;
        }
        for (String line : note.split("\\R")) {
            for (String sentence : line.split("(?<=[.!?])\\s+")) {
                String trimmed = sentence.strip();
                if (!trimmed.isEmpty()) {
                    lines.add(cutAtWord(trimmed));
                }
            }
        }
        return lines;
    }

    /**
     * The sentences left once {@code line} is taken out, or empty when the note has no such
     * sentence. Only the first copy goes when the note says the same thing twice.
     */
    public static Optional<List<String>> without(String note, String line) {
        List<String> lines = lines(note);
        if (line == null || !lines.remove(line)) {
            return Optional.empty();
        }
        return Optional.of(lines);
    }

    /** What {@code users.user_context} holds for these sentences: null once none are left. */
    public static String join(List<String> lines) {
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    static String cutAtWord(String text) {
        if (text.length() <= LINE_MAX) {
            return text;
        }
        if (Character.isWhitespace(text.charAt(LINE_MAX))) {
            return text.substring(0, LINE_MAX).stripTrailing();
        }
        String head = text.substring(0, LINE_MAX);
        for (int i = head.length() - 1; i > 0; i--) {
            if (Character.isWhitespace(head.charAt(i))) {
                return head.substring(0, i).stripTrailing();
            }
        }
        // One word longer than a line: nothing to cut at but the limit.
        return head;
    }
}
