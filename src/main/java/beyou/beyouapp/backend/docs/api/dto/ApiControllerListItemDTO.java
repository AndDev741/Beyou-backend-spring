package beyou.beyouapp.backend.docs.api.dto;

import java.sql.Date;

public record ApiControllerListItemDTO(
    String key,
    String title,
    String summary,
    int orderIndex,
    Date updatedAt
) {
}