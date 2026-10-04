package beyou.beyouapp.backend.domain.notebook.ai.dto;

import java.util.UUID;

/**
 * What a [n] in an answer points at.
 *
 * @param kind     SOURCE (a chunk of a source; {@code sourceId} and {@code chunkId} are set) or
 *                 PAGE (the person's own notes; {@code pageId} is set)
 * @param excerpt  the start of the passage, for the popover; the full text is one call away
 */
public record CitationDTO(
        int n,
        String kind,
        UUID sourceId,
        UUID chunkId,
        UUID pageId,
        String title,
        Integer pageNumber,
        String excerpt) {
}
