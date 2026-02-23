package beyou.beyouapp.backend.docs.search.dto;

public record SearchRequestDTO(
    String q,
    String locale,
    String category,   // "all", "architecture", "design", "api", "project"
    Integer limit,
    Integer offset
) {
    public SearchRequestDTO {
        if (q == null || q.trim().isEmpty()) {
            throw new IllegalArgumentException("Search query 'q' is required");
        }
        if (locale == null) {
            locale = "en";
        }
        if (category == null) {
            category = "all";
        }
        if (limit == null || limit <= 0) {
            limit = 10;
        }
        if (offset == null || offset < 0) {
            offset = 0;
        }
    }
}