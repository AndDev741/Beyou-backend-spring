package beyou.beyouapp.backend.domain.routine.schedule.dto;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateScheduleDTO(
    @NotEmpty Set<@Pattern(regexp = "^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)$") String> days,
    @NotNull UUID routineId
) {}
