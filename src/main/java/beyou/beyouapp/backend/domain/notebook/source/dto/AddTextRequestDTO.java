package beyou.beyouapp.backend.domain.notebook.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Pasted text: lecture notes, a transcript, an article that will not load as a link. */
public record AddTextRequestDTO(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 200_000) String text) {
}
