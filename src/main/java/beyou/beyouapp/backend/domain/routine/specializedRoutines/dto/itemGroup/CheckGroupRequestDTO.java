package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup;

import java.util.UUID;

public record CheckGroupRequestDTO(UUID routineId, TaskGroupRequestDTO taskGroupDTO, HabitGroupRequestDTO habitGroupDTO) {
    
}
