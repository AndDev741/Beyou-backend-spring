package beyou.beyouapp.backend.domain.notebook.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The whole document, as the editor's autosave sends it. Two million characters is a long book;
 * the bound exists so one request cannot be a heap.
 */
public record UpdateContentRequestDTO(@NotNull @Size(max = 2_000_000) String content) {
}
