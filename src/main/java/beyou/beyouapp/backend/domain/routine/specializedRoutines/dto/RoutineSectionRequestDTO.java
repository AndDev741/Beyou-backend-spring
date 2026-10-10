package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * One section of a DAILY routine. {@code name} and {@code iconId} are varchar(255) in
 * routine_sections; a blank name stays the service's ROUTINE_SECTION_NAME_REQUIRED.
 */
public record RoutineSectionRequestDTO(UUID id, @Size(max = 255) String name, @Size(max = 255) String iconId,
        LocalTime startTime, LocalTime endTime,
        @Size(max = DiaryRoutineRequestDTO.MAX_ITEMS) List<TaskGroupDTO> taskGroup,
        @Size(max = DiaryRoutineRequestDTO.MAX_ITEMS) List<HabitGroupDTO> habitGroup,
        Boolean favorite) {

}
