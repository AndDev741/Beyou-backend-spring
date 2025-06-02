package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.time.LocalTime;
import java.util.List;

public record RoutineSectionRequestDTO(String name, String iconId, LocalTime startTime, LocalTime endTime, List<TaskGroupDTO> taskGroup, List<HabitGroupDTO> habitGroup) {

}
