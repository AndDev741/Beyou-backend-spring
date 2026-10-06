package beyou.beyouapp.backend.domain.notebook.dto;

import java.util.List;

/**
 * The "Review" card on the home.
 *
 * @param streak consecutive days, ending today or yesterday, with at least one review
 */
public record ReviewSummaryDTO(int due, List<TopicDueDTO> byTopic, int streak) {
}
