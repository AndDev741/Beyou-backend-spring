package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A topic in a cycle's configuration.
 *
 * @param minutesOverride the person's own minutes per lap, or null for the planner's suggestion
 */
public record CycleTopicDTO(
        @NotNull UUID topicId,
        @NotNull @Min(1) @Max(5) Integer importance,
        @NotNull @Min(1) @Max(5) Integer mastery,
        @Min(5) @Max(600) Integer minutesOverride) {
}
