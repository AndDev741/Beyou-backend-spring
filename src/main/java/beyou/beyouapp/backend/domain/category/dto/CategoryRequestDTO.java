package beyou.beyouapp.backend.domain.category.dto;

import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import jakarta.validation.constraints.*;

import java.util.UUID;

public record CategoryRequestDTO(
        UUID id,
        @NotEmpty @Size(min = 2, max = 256) String name,
        @NotBlank String icon,
        String description,
        @NotNull ExperienceLevel experience) {
}
