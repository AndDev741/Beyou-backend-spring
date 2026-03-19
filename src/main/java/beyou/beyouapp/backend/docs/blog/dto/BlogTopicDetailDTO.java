package beyou.beyouapp.backend.docs.blog.dto;

import java.sql.Date;

public record BlogTopicDetailDTO(
    String key, String title, String docMarkdown, String category,
    String tags, boolean featured, Date publishedAt,
    String coverColor, String coverEmoji, String author, Date updatedAt
) {}
