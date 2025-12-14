package beyou.beyouapp.backend.domain.routine.schedule.dto;

import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record CreateScheduleDTO(
    @NotEmpty Set<WeekDay> days,
    @NotNull UUID routineId
) {}
