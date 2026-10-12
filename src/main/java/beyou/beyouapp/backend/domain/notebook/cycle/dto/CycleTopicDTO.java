package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A subject in a cycle's configuration. The field keeps the name {@code topicId} from when only a
 * topic could be one; it names any notebook page of the caller's now, a topic or a page inside
 * one, as long as it neither contains nor sits inside another subject of an active cycle.
 *
 * @param minutesOverride the person's own minutes per lap, or null for the planner's suggestion
 */
public record CycleTopicDTO(
        @NotNull UUID topicId,
        @NotNull @Min(1) @Max(5) Integer importance,
        @NotNull @Min(1) @Max(5) Integer mastery,
        @Min(5) @Max(600) Integer minutesOverride) {
}
