package beyou.beyouapp.backend.domain.notebook.card.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PATCH semantics. Editing a card's text keeps its schedule. */
public record UpdateCardRequestDTO(
        @Size(max = 2000) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String front,
        @Size(max = 2000) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String back) {
}
