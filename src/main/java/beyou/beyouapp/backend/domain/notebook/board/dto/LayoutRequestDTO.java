package beyou.beyouapp.backend.domain.notebook.board.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Many positions at once: "Tidy up", or a drag of several selected nodes. Ids that are not on
 * the board are ignored rather than refused, so a node deleted in another tab does not turn a
 * drag into an error.
 */
public record LayoutRequestDTO(@NotNull @Size(max = 500) List<@Valid NodePositionDTO> positions) {
}
