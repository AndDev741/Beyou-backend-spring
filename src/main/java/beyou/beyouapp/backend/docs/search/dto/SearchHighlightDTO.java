package beyou.beyouapp.backend.docs.search.dto;

import java.util.List;

public record SearchHighlightDTO(
    List<String> title,
    List<String> summary
) {
}