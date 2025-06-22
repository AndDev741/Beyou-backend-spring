package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;

public record DiaryRoutineResponseDTO(UUID id, String name, String iconId, List<RoutineSectionResponseDTO> routineSections, Schedule schedule) {

    public record RoutineSectionResponseDTO(UUID id, String name, String iconId, String startTime, String endTime,
                                            List<TaskGroupResponseDTO> taskGroup, List<HabitGroupResponseDTO> habitGroup) {

        public record TaskGroupResponseDTO(UUID id, UUID taskId, String startTime, List<TaskGroupCheck> taskGroupChecks) {
        }

        public record HabitGroupResponseDTO(UUID id, UUID habitId, String startTime, List<HabitGroupCheck> habitGroupChecks) {
        }
    }
}