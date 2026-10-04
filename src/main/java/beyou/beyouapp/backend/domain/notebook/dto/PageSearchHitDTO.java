package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.NotebookPageKind;

/** A page found by title, with its topic, for the "link an existing page" picker. */
public record PageSearchHitDTO(UUID id, String title, String icon, NotebookPageKind kind,
        UUID topicId, String topicTitle) {
}
