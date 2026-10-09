package beyou.beyouapp.backend.domain.notebook.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The whole document, as the editor's autosave sends it. Two million characters is a long book;
 * the bound exists so one request cannot be a heap.
 *
 * @param baseRevision the page's {@code contentRevision} the editor started from. A save from an
 *                     older revision is refused with NOTEBOOK_CONTENT_CONFLICT. Null is the
 *                     client from before revisions, which still overwrites; every current client
 *                     sends it.
 */
public record UpdateContentRequestDTO(@NotNull @Size(max = 2_000_000) String content, @PositiveOrZero Long baseRevision) {
}
