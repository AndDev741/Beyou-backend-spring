package beyou.beyouapp.backend.domain.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CategoryEditRequestDTO(
        @NotBlank String categoryId,
        @NotEmpty @Size(min = 2, max = 256) String name,
        @NotBlank String icon,
        @Size(max = 1024) String description
) {}
