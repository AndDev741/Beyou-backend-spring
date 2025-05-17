package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.time.LocalTime;
import java.util.UUID;

public record HabitGroupDTO(UUID habitId, LocalTime startTime)  {

}
