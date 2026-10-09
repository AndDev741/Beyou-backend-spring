package beyou.beyouapp.backend.domain.notebook.board.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Every page node on a board, in the order the path should take. The board becomes that one path:
 * see {@code NotebookBoardService.restructure}.
 */
public record BoardOrderRequestDTO(@NotNull @Size(max = 500) List<@NotNull UUID> order) {
}
