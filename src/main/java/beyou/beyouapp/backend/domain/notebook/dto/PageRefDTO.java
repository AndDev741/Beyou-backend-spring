package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

/** A page named somewhere else: a breadcrumb step, the next node to study, a search hit. */
public record PageRefDTO(UUID id, String title, String icon) {
}
