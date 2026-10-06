package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

/** A goal, category or habit a topic is linked to, with the name the chip shows. */
public record LinkRefDTO(UUID id, String name) {
}
