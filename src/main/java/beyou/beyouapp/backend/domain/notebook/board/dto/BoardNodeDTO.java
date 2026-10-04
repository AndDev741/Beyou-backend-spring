package beyou.beyouapp.backend.domain.notebook.board.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import beyou.beyouapp.backend.domain.notebook.dto.ProgressDTO;

/**
 * A box on a board, with what the box shows.
 *
 * @param title          the page's title for a PAGE node, the label for a SECTION
 * @param linked         the page's home is another topic; deleting the node only unlinks it
 * @param homeTopicTitle that topic's title, when linked
 * @param hasBoard       the node's page has a board of its own, so the box shows "4 of 8"
 */
public record BoardNodeDTO(
        UUID id,
        NotebookNodeKind kind,
        UUID pageId,
        String title,
        String icon,
        NotebookStatus status,
        ProgressDTO progress,
        boolean hasBoard,
        double x,
        double y,
        Double width,
        Double height,
        boolean linked,
        String homeTopicTitle) {
}
