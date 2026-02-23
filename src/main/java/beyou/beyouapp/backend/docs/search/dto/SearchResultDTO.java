package beyou.beyouapp.backend.docs.search.dto;

import java.sql.Date;

public record SearchResultDTO(
    String type,           // "architecture", "design", "api", "project"
    String key,
    String title,
    String summary,
    Date updatedAt,
    double score,
    SearchHighlightDTO highlight
) {
}