package beyou.beyouapp.backend.domain.notebook.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddLinkRequestDTO(@NotBlank @Size(max = 2048) String url) {
}
