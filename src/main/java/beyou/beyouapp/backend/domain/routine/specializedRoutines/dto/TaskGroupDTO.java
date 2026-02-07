package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;

public record TaskGroupDTO(UUID id, UUID taskId, LocalTime startTime, LocalTime endTime, List<TaskGroupCheck> taskGroupCheck)  {

}
