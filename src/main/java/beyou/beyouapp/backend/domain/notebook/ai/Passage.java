package beyou.beyouapp.backend.domain.notebook.ai;

import java.util.UUID;

/**
 * One numbered passage put in front of the model, and what a citation of it points at.
 *
 * @param kind     SOURCE for a chunk of a source, PAGE for the person's own notes
 * @param label    how the passage is introduced to the model and titled on the citation
 */
public record Passage(
        int number,
        String kind,
        UUID sourceId,
        UUID chunkId,
        UUID pageId,
        String label,
        Integer pageNumber,
        String text) {

    public static final String SOURCE = "SOURCE";
    public static final String PAGE = "PAGE";
}
