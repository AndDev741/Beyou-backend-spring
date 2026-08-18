package beyou.beyouapp.backend.domain.goal.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;

public record CreateGoalRequestDTO(
    @NotEmpty @Size(min = 2, max = 255)
    String name,
    @Size(max = 255)
    String description,
    String iconId,
    @NotNull
    Double targetValue,
    @NotBlank @Size(max = 255)
    String unit,
    @NotNull
    Double currentValue,
    @NotNull
    List<UUID> categoriesId,
    @Size(max = 255)
    String motivation,
    @NotNull
    LocalDate startDate,
    @NotNull
    LocalDate endDate,
    @NotNull
    GoalStatus status,
    @NotNull
    GoalTerm term
) {
}