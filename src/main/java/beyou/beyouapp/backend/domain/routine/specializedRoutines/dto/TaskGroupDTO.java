package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.time.LocalTime;
import java.util.UUID;

public record TaskGroupDTO(UUID taskId, LocalTime startTime)  {

}
