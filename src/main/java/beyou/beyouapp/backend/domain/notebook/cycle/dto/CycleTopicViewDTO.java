package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;


/** A subject of a cycle as it is configured, with the page it names. */
public record CycleTopicViewDTO(UUID topicId, int importance, int mastery, Integer minutesOverride,
        SubjectRefDTO topic) {
}
