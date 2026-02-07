package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;

public record HabitGroupDTO(UUID id, UUID habitId, LocalTime startTime, LocalTime endTime, List<HabitGroupCheck> habitGroupCheck)  {

}
