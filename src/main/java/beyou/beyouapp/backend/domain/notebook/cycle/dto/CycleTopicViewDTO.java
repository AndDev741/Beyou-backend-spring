package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.dto.PageRefDTO;

/** A topic of a cycle as it is configured, with the topic page it names. */
public record CycleTopicViewDTO(UUID topicId, int importance, int mastery, Integer minutesOverride,
        PageRefDTO topic) {
}
