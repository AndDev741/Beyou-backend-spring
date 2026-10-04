package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.NotebookStatus;

/**
 * One row of a topic's page tree.
 *
 * @param onBoard whether the page is a node on its parent's board; the sidebar lists the
 *                others under "Pages off the board"
 * @param linked  whether the row is a node pointing at a page whose home is another topic. Its
 *                own children are listed in that topic, not here.
 */
public record TreeItemDTO(
        UUID id,
        UUID parentId,
        String title,
        String icon,
        NotebookStatus status,
        boolean onBoard,
        boolean linked,
        ProgressDTO progress,
        int position) {
}
