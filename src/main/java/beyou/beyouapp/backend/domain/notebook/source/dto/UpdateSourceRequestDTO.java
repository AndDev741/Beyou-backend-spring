package beyou.beyouapp.backend.domain.notebook.source.dto;

import jakarta.validation.constraints.NotNull;

/** Turns a source on or off for answers without deleting it. */
public record UpdateSourceRequestDTO(@NotNull Boolean enabled) {
}
