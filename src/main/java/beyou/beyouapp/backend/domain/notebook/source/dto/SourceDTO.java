package beyou.beyouapp.backend.domain.notebook.source.dto;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceKind;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceStatus;

/**
 * A source as the study room lists it.
 *
 * @param inherited the source was added to a page above this one and is read from here too
 * @param errorKey  the ErrorKey name when the source FAILED, for the client to translate
 */
public record SourceDTO(
        UUID id,
        UUID pageId,
        String pageTitle,
        boolean inherited,
        NotebookSourceKind kind,
        String title,
        String url,
        NotebookSourceStatus status,
        int progress,
        String errorKey,
        boolean enabled,
        Integer pageCount,
        Integer charCount,
        Instant createdAt) {
}
