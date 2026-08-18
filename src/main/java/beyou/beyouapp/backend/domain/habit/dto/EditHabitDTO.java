package beyou.beyouapp.backend.domain.habit.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record EditHabitDTO(
        UUID habitId,
        @Size(max = 256) String name,
        @Size(max = 256) String description,
        @Size(max = 256) String motivationalPhrase,
        String iconId,
        @NotNull @Min(1) @Max(5) Integer importance,
        // The wire name is the (misspelled) "dificulty"; the alias tolerates the
        // correctly spelled form the AI agent tends to send.
        @JsonAlias("difficulty") @NotNull @Min(1) @Max(5) Integer dificulty,
        @NotEmpty(message = "At least one category is required") List<UUID> categoriesId) {
}
