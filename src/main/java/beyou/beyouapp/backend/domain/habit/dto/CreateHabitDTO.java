package beyou.beyouapp.backend.domain.habit.dto;

import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateHabitDTO(
        String name,
        String description,
        String motivationalPhrase,
        String iconId,
        @NotNull @Min(1) @Max(5) Integer importance,
        @NotNull @Min(1) @Max(5) Integer dificulty,
        List<UUID> categoriesId,
        @NotNull ExperienceLevel experience) {
}
