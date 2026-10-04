package beyou.beyouapp.backend.domain.notebook.ai.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * "Create topic with N nodes": the reviewed draft, created in one transaction. Not an AI call;
 * it lives beside the draft because it is the draft's other half.
 */
public record CreateFromDraftRequestDTO(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 512) String description,
        @Size(max = 64) String icon,
        UUID goalId,
        UUID categoryId,
        UUID habitId,
        @NotEmpty @Size(max = 20) List<@Valid DraftNodeInputDTO> nodes) {
}
