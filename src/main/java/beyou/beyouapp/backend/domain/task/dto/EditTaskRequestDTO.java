package beyou.beyouapp.backend.domain.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record EditTaskRequestDTO(
        UUID taskId,
        String name,
        String description,
        String iconId,
        @NotNull @Min(1) @Max(5) Integer importance,
        @NotNull @Min(1) @Max(5) Integer difficulty,
        List<UUID> categoriesId,
        boolean oneTimeTask) {
}
