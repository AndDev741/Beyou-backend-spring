package beyou.beyouapp.backend.domain.notebook.source.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceKind;

/**
 * One cited passage with the text either side of it, for "Open at page N".
 *
 * @param before the previous chunk's text, or null at the start of the source
 * @param after  the next chunk's text, or null at the end
 */
public record PassageDTO(
        UUID sourceId,
        String sourceTitle,
        NotebookSourceKind kind,
        String url,
        UUID chunkId,
        Integer pageNumber,
        String text,
        String before,
        String after) {
}
