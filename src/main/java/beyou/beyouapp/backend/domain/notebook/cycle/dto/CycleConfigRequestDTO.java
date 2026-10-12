package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create a cycle, or change one. The name's length is checked after trimming, by the service.
 *
 * @param days   exactly seven, one per weekday
 * @param topics 1..30 subjects (notebook pages, a topic or any page inside one), in display
 *               order. None may contain or sit inside another one, here
 *               (STUDY_CYCLE_TOPIC_OVERLAP) or in another active cycle (STUDY_CYCLE_TOPIC_TAKEN).
 *               More than 30 is refused by the service as STUDY_CYCLE_LIMIT, the key of the
 *               cycle's other limits, rather than here as a plain validation error
 */
public record CycleConfigRequestDTO(
        @NotBlank String name,
        @NotNull BlockMode defaultMode,
        @NotNull @Min(15) @Max(240) Integer maxBlockMinutes,
        @NotNull @Size(min = 7, max = 7) List<@Valid @NotNull CycleDayDTO> days,
        @NotNull @Size(min = 1) List<@Valid @NotNull CycleTopicDTO> topics) {
}
