package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;

public record DiaryRoutineResponseDTO(
        UUID id,
        String name,
        String iconId,
        List<RoutineSectionResponseDTO> routineSections,
        Schedule schedule,
        double xp,
        double actualLevelXp,
        double nextLevelXp,
        int level) {

    public record RoutineSectionResponseDTO(
            UUID id,
            String name,
            String iconId,
            String startTime,
            String endTime,
            List<TaskGroupResponseDTO> taskGroup,
            List<HabitGroupResponseDTO> habitGroup,
            boolean favorite) {

        public record TaskGroupResponseDTO(
                UUID id,
                UUID taskId,
                String startTime,
                String endTime,
                List<TaskGroupCheck> taskGroupChecks) {
        }

        public record HabitGroupResponseDTO(
                UUID id,
                UUID habitId,
                String startTime,
                String endTime,
                List<HabitGroupCheck> habitGroupChecks) {
        }
    }
}
