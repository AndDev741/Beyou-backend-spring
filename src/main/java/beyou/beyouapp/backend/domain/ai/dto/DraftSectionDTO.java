package beyou.beyouapp.backend.domain.ai.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DraftSectionDTO(
        @NotBlank String name,
        String iconId,
        @NotNull String startTime,
        String endTime,
        @Valid List<DraftHabitItemDTO> habits,
        @Valid List<DraftTaskItemDTO> tasks) {
}
