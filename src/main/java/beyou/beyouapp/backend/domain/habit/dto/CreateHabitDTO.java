package beyou.beyouapp.backend.domain.habit.dto;

import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateHabitDTO(
        @NotBlank(message = "Name is Required") @Size(min = 2, max = 256, message = "Name must be between 2 and 256 characters") String name,
        @Size(max = 1000, message = "Description is too long") String description,
        @Size(max = 500, message = "Motivational phrase is too long") String motivationalPhrase,
        @NotBlank(message = "Icon is Required") String iconId,
        @NotNull @Min(1) @Max(5) Integer importance,
        @NotNull @Min(1) @Max(5) Integer dificulty,
        List<UUID> categoriesId,
        @NotNull ExperienceLevel experience) {
}
