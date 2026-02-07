package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup;

import java.time.LocalDate;
import java.util.UUID;

public record SkipGroupRequestDTO(
        UUID routineId,
        TaskGroupRequestDTO taskGroupDTO,
        HabitGroupRequestDTO habitGroupDTO,
        LocalDate date,
        boolean skip) {

}
