package beyou.beyouapp.backend.domain.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** tempKey ("new-1", "new-2"...) is how draft items reference this category before it has a UUID. */
public record DraftNewCategoryDTO(
        @NotBlank String tempKey,
        @NotBlank @Size(min = 2, max = 256) String name,
        String icon,
        @Size(max = 256) String description) {
}
