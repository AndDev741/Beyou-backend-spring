package beyou.beyouapp.backend.domain.routine.schedule.dto;

import java.util.Set;
import java.util.UUID;

public record UpdateScheduleDTO(UUID scheduleId, Set<String> days, UUID routineId ) {
    
}
