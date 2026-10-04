package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.List;

/** The notebook home in one call. {@code continueStudying} is null until a page has been opened. */
public record HomeResponseDTO(List<TopicSummaryDTO> topics, ContinueDTO continueStudying, ReviewSummaryDTO review) {
}
