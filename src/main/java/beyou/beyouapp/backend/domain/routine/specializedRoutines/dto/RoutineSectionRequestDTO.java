package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record RoutineSectionRequestDTO(UUID id, String name, String iconId, LocalTime startTime, LocalTime endTime, List<TaskGroupDTO> taskGroup, List<HabitGroupDTO> habitGroup) {

}
