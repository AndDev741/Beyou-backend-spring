package beyou.beyouapp.backend.domain.notebook.card.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCardRequestDTO(
        @NotBlank @Size(max = 2000) String front,
        @NotBlank @Size(max = 2000) String back,
        @Size(max = 255) String sourceLabel) {
}
