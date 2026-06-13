package beyou.beyouapp.backend.domain.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * previousDraft + feedback enable stateless re-generation: the frontend sends
 * back the current draft plus an adjustment instruction; no server-side
 * conversation state exists.
 */
public record GenerateRoutineRequestDTO(
        @NotBlank @Size(min = 10, max = 2000) String description,
        @Valid RoutineDraftDTO previousDraft,
        @Size(max = 500) String feedback,
        @Pattern(regexp = "en|pt") String language) {
}
