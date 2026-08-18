package beyou.beyouapp.backend.domain.task.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record EditTaskRequestDTO(
        UUID taskId,
        @Size(max = 255) String name,
        @Size(max = 255) String description,
        String iconId,
        @NotNull @Min(1) @Max(5) Integer importance,
        @JsonAlias("dificulty") @NotNull @Min(1) @Max(5) Integer difficulty,
        List<UUID> categoriesId,
        boolean oneTimeTask) {
}
