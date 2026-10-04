package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A page under {@code parentId} that is not on its board ("Add a page" in the sidebar). */
public record CreatePageRequestDTO(
        @NotNull UUID parentId,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 64) String icon) {
}
