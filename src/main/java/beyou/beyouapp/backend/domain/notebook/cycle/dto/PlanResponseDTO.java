package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.List;
import java.util.UUID;

/**
 * What the planner made of a week and a list of topics. See {@code StudyCyclePlanner}.
 *
 * @param lapDays days one lap takes at the week's pace, null with no minutes in the week
 */
public record PlanResponseDTO(int weekMinutes, int plannedMinutes, Integer lapDays, List<Topic> topics,
        List<Block> blocks) {

    /** @param share the topic's weight over the sum of weights, 0..1; @param blocks how many blocks */
    public record Topic(UUID topicId, int weight, double share, int suggestedMinutes, int minutes, int blocks) {
    }

    public record Block(int position, UUID topicId, int plannedMinutes) {
    }
}
