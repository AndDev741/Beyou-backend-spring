package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup;

import java.time.LocalTime;
import java.util.UUID;

public record HabitGroupRequestDTO(UUID habitGroupId, LocalTime startTime) {
    
}
