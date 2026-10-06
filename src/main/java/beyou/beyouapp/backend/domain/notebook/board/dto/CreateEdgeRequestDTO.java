package beyou.beyouapp.backend.domain.notebook.board.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** "Study {@code source} before {@code target}". Both must be PAGE nodes on the same board. */
public record CreateEdgeRequestDTO(@NotNull UUID source, @NotNull UUID target) {
}
