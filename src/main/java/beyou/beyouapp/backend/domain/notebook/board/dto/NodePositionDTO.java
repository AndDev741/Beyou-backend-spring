package beyou.beyouapp.backend.domain.notebook.board.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record NodePositionDTO(@NotNull UUID nodeId, @NotNull Double x, @NotNull Double y) {
}
