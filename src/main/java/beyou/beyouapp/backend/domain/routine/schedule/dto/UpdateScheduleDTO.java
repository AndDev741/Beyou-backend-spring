package beyou.beyouapp.backend.domain.routine.schedule.dto;

import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;

import java.util.Set;
import java.util.UUID;

public record UpdateScheduleDTO(UUID scheduleId, Set<WeekDay> days, UUID routineId ) {
    
}
