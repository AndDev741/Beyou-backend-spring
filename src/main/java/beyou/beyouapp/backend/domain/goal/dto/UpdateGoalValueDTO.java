package beyou.beyouapp.backend.domain.goal.dto;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateGoalValueDTO(
    @NotNull
    UUID goalId,
    @Positive
    Double value
) {
}
