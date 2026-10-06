package beyou.beyouapp.backend.domain.notebook.board.dto;

import jakarta.validation.constraints.Size;

/** PATCH semantics: a null field is left as it is. The label only means something on a section. */
public record UpdateNodeRequestDTO(Double x, Double y, Double width, Double height, @Size(max = 255) String label) {
}
