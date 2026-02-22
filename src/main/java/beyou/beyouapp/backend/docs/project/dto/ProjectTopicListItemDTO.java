package beyou.beyouapp.backend.docs.project.dto;

import java.sql.Date;

public record ProjectTopicListItemDTO(
    String key,
    String title,
    String summary,
    int orderIndex,
    Date updatedAt,
    String status,
    String tags // JSON string, can be parsed client‑side
) {}