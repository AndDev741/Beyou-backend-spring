package beyou.beyouapp.backend.domain.notebook.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.NotebookPageKind;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;

/**
 * One page as the page screen draws it: the document, where it sits, and the numbers in the
 * chips under the title.
 *
 * @param breadcrumb    the topic first, this page last
 * @param hasBoard      whether the page's board has any node, which is what decides if its
 *                      status follows the nodes
 * @param focusMinutes  completed pomodoro minutes on this page and every page under it
 * @param cardsDue      cards on this page due today or earlier, in the owner's day
 * @param sourcesCount  sources this page can read: its own plus every ancestor's
 * @param contentRevision the document's revision; the editor sends it back with its next save
 */
public record PageResponseDTO(
        UUID id,
        NotebookPageKind kind,
        UUID topicId,
        UUID parentId,
        String title,
        String icon,
        String description,
        String content,
        NotebookStatus status,
        boolean statusManual,
        boolean hasBoard,
        ProgressDTO progress,
        List<PageRefDTO> breadcrumb,
        LinkRefDTO goal,
        LinkRefDTO category,
        LinkRefDTO habit,
        int focusMinutes,
        int cardsTotal,
        int cardsDue,
        int sourcesCount,
        Instant updatedAt,
        long contentRevision) {
}
