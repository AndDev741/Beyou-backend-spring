package beyou.beyouapp.backend.domain.notebook.board.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import jakarta.validation.constraints.Size;

/**
 * A new box on a board.
 *
 * <ul>
 *   <li>PAGE with {@code title}: a new child page, shown by the node.</li>
 *   <li>PAGE with {@code linkPageId}: an existing page, kept where it lives, shown here too.</li>
 *   <li>SECTION with {@code label}, and optionally a size.</li>
 * </ul>
 * Exactly one of title and linkPageId for a PAGE; the service says which is missing.
 *
 * <p>{@code x} and {@code y} go together. A client that does not draw the board (the phone, the
 * assistant) leaves both out and the node goes on the next free grid cell. {@code after} is a
 * page node on the same board to link to the new one, so the node lands after it on the path.
 */
public record CreateNodeRequestDTO(
        NotebookNodeKind kind,
        @Size(max = 255) String title,
        UUID linkPageId,
        @Size(max = 255) String label,
        Double x,
        Double y,
        Double width,
        Double height,
        UUID after) {
}
