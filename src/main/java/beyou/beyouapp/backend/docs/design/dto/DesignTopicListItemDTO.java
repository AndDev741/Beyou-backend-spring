package beyou.beyouapp.backend.docs.design.dto;

import java.sql.Date;

public record DesignTopicListItemDTO(
    String key,
    String title,
    String summary,
    Integer orderIndex,
    Date updatedAt
) {

}
