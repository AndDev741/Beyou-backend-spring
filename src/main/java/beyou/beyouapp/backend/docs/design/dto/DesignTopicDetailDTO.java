package beyou.beyouapp.backend.docs.design.dto;

import java.sql.Date;

public record DesignTopicDetailDTO(
    String key,
    String title,
    String docMarkdown,
    Date updatedAt
) {

}
