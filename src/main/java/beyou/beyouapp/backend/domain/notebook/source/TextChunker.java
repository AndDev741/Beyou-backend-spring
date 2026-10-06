package beyou.beyouapp.backend.domain.notebook.source;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts text into the passages the study AI reads and cites.
 *
 * <p>Chunks follow paragraphs, packed up to about {@link #TARGET} characters, and a paragraph
 * longer than {@link #MAX} is split at sentence ends. No overlap between chunks: a citation opens
 * one chunk as an excerpt, and an excerpt that starts mid-way through the previous one's last
 * sentence reads like a mistake. Retrieval returns several chunks per answer, so a thought that
 * straddles two of them still arrives whole.
 */
public final class TextChunker {

    static final int TARGET = 1000;
    static final int MAX = 1600;
    /** Chunks one source may produce. A 600-page book fits; a scraped site map does not. */
    public static final int MAX_CHUNKS = 3000;

    private TextChunker() {}

    /** One page of text and its number (null when the source has no pages). */
    public record PageText(Integer pageNumber, String text) {
    }

    public record Chunk(int ordinal, Integer pageNumber, String content) {
    }

    public static List<Chunk> chunk(List<PageText> pages) {
        List<Chunk> chunks = new ArrayList<>();
        for (PageText page : pages) {
            if (page.text() == null) continue;
            StringBuilder current = new StringBuilder();
            for (String paragraph : page.text().split("\\n\\s*\\n|\\r?\\n(?=\\s*[-*•\\d])")) {
                String p = paragraph.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\s*\\n\\s*", " ").strip();
                if (p.isEmpty()) continue;
                for (String piece : splitLong(p)) {
                    if (current.length() > 0 && current.length() + piece.length() + 1 > TARGET) {
                        add(chunks, page.pageNumber(), current);
                    }
                    if (current.length() > 0) current.append('\n');
                    current.append(piece);
                }
                if (chunks.size() >= MAX_CHUNKS) return chunks;
            }
            add(chunks, page.pageNumber(), current);
            if (chunks.size() >= MAX_CHUNKS) return chunks;
        }
        return chunks;
    }

    private static void add(List<Chunk> chunks, Integer pageNumber, StringBuilder current) {
        String content = current.toString().strip();
        current.setLength(0);
        if (content.isEmpty() || chunks.size() >= MAX_CHUNKS) return;
        chunks.add(new Chunk(chunks.size(), pageNumber, content));
    }

    /** A paragraph within {@link #MAX} as is; a longer one cut at sentence ends, then hard. */
    static List<String> splitLong(String paragraph) {
        if (paragraph.length() <= MAX) return List.of(paragraph);
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : paragraph.split("(?<=[.!?])\\s+")) {
            if (current.length() > 0 && current.length() + sentence.length() + 1 > TARGET) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (sentence.length() > MAX) {
                for (int i = 0; i < sentence.length(); i += TARGET) {
                    pieces.add(sentence.substring(i, Math.min(sentence.length(), i + TARGET)));
                }
                continue;
            }
            if (current.length() > 0) current.append(' ');
            current.append(sentence);
        }
        if (current.length() > 0) pieces.add(current.toString());
        return pieces;
    }
}
