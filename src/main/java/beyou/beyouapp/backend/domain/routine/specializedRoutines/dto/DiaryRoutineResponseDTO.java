package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.util.List;
import java.util.UUID;

public record DiaryRoutineResponseDTO(UUID id, String name, String iconId, List<RoutineSectionResponseDTO> routineSections) {

    public record RoutineSectionResponseDTO(UUID id, String name, String iconId, String startTime, String endTime,
                                            List<TaskGroupResponseDTO> taskGroup, List<HabitGroupResponseDTO> habitGroup) {

        public record TaskGroupResponseDTO(UUID id, UUID taskId, String startTime) {
        }

        public record HabitGroupResponseDTO(UUID id, UUID habitId, String startTime) {
        }
    }
}