package beyou.beyouapp.backend.docs.architecture.dto;

import java.sql.Date;

public record ArchitectureTopicListItemDTO(
    String key,
    String title,
    String summary,
    int orderIndex,
    Date updatedAt
) {

}
