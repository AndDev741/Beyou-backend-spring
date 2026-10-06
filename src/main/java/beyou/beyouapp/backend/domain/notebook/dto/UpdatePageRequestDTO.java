package beyou.beyouapp.backend.domain.notebook.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH semantics: a null field is left as it is. A blank icon or description clears it; a
 * title cannot be blank, because the tree and the board have nothing else to show.
 */
public record UpdatePageRequestDTO(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String title,
        @Size(max = 64) String icon,
        @Size(max = 512) String description) {
}
