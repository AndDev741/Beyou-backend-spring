package beyou.beyouapp.backend.domain.notebook.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequestDTO(@NotBlank @Size(max = 2000) String message) {
}
