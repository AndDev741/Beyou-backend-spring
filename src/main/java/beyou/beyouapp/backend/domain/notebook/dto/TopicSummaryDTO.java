package beyou.beyouapp.backend.domain.notebook.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One card on the notebook home.
 *
 * @param next    the leaf to study next: the first one being studied, else the first not started
 * @param preview the topic's own board, for the thumbnail. Nodes only, no sections.
 */
public record TopicSummaryDTO(
        UUID id,
        String title,
        String icon,
        String description,
        ProgressDTO progress,
        int cardsDue,
        int sourcesCount,
        PageRefDTO next,
        LinkRefDTO goal,
        LinkRefDTO habit,
        List<MiniNodeDTO> preview,
        List<MiniEdgeDTO> previewEdges,
        Instant updatedAt) {
}
