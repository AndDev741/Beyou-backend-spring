package beyou.beyouapp.backend.docs.api.dto;

import java.sql.Date;

public record ApiControllerDetailDTO(
    String key,
    String title,
    String summary,
    String apiCatalog,
    Date updatedAt
) {
}