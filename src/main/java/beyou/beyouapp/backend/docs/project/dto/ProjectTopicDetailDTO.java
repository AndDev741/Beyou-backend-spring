package beyou.beyouapp.backend.docs.project.dto;

import java.sql.Date;

public record ProjectTopicDetailDTO(
    String key,
    String title,
    String docMarkdown,
    String diagramMermaid,
    String designTopicKey,
    String architectureTopicKey,
    String repositoryUrl,
    String tags, // JSON string
    Date updatedAt
) {}