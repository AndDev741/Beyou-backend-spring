package beyou.beyouapp.backend.docs.architecture.dto;

import java.sql.Date;

public record ArchitectureTopicDetailDTO(
    String key,
    String title,
    String diagramMermaid,
    String docMarkdown,
    Date updatedAt
) {

}
