package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new, empty topic. The links are optional and can be set later. */
public record CreateTopicRequestDTO(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 512) String description,
        @Size(max = 64) String icon,
        UUID goalId,
        UUID categoryId,
        UUID habitId) {
}
