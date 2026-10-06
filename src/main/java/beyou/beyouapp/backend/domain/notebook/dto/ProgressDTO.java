package beyou.beyouapp.backend.domain.notebook.dto;

/** Leaves done out of leaves in total. See {@code ProgressGraph}. */
public record ProgressDTO(int done, int total) {
}
