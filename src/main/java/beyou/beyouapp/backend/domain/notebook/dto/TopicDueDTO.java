package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

/** Cards due in one topic. */
public record TopicDueDTO(UUID topicId, String title, int due) {
}
