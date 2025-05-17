package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.util.List;

public record DiaryRoutineRequestDTO(String name, String iconId, List<RoutineSectionRequestDTO> routineSections) {
    
}
