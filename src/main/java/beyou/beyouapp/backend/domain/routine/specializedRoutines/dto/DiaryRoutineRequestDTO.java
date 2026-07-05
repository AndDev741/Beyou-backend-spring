package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.util.List;
import java.util.UUID;

public record DiaryRoutineRequestDTO(UUID id, String name, String iconId, List<RoutineSectionRequestDTO> routineSections) {

}
