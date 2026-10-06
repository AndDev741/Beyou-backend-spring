package beyou.beyouapp.backend.domain.notebook.dto;

import jakarta.validation.constraints.NotNull;

public record SetStatusRequestDTO(@NotNull StatusChoice status) {
}
