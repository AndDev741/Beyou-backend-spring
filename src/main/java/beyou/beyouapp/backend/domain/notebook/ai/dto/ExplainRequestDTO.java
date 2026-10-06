package beyou.beyouapp.backend.domain.notebook.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "Explain the block above": the block's text. */
public record ExplainRequestDTO(@NotBlank @Size(max = 4000) String text) {
}
