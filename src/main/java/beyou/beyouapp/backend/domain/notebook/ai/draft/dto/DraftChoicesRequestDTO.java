package beyou.beyouapp.backend.domain.notebook.ai.draft.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The ticks in the review dialog, one per drafted node, saved as the person changes them. */
public record DraftChoicesRequestDTO(@NotNull @Size(max = 20) List<@NotNull DraftChoiceDTO> choices) {
}
