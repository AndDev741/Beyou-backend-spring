package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

/** PUT semantics: the three links as they should be. A null id removes that link. */
public record TopicLinksRequestDTO(UUID goalId, UUID categoryId, UUID habitId) {
}
