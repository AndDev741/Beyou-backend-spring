package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup;

import java.time.LocalTime;
import java.util.UUID;

public record TaskGroupRequestDTO(UUID taskGroupId, LocalTime startTime) {
    
}
