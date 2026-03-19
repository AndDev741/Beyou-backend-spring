package beyou.beyouapp.backend.docs.blog.dto;

import java.sql.Date;

public record BlogTopicListItemDTO(
    String key, String title, String summary, String category,
    String tags, boolean featured, Date publishedAt,
    String coverColor, String coverEmoji, String author, Date updatedAt
) {}
