package beyou.beyouapp.backend.domain.notebook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Markdown to add at the end of a page, as blocks. "Save to page" from the study room. */
public record AppendRequestDTO(@NotBlank @Size(max = 20_000) String markdown) {
}
