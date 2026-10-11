package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The editor's live preview. Stateless: nothing is read or written, so the topics are not
 * checked against the notebook here, and an empty list (an editor with no topic picked yet)
 * plans to nothing rather than failing.
 */
public record PlanRequestDTO(
        @NotNull @Size(min = 7, max = 7) List<@Valid @NotNull CycleDayDTO> days,
        @NotNull @Min(15) @Max(240) Integer maxBlockMinutes,
        @NotNull @Size(max = 30) List<@Valid @NotNull CycleTopicDTO> topics) {
}
