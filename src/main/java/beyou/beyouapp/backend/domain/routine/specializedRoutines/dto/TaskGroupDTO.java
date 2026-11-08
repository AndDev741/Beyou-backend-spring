package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;

/**
 * One task inside a DAILY routine's section, as a client sends it. {@code days} follows the
 * same rule as {@link HabitGroupDTO#days()}: null keeps, empty clears.
 */
public record TaskGroupDTO(UUID id, UUID taskId, LocalTime startTime, LocalTime endTime,
        List<TaskGroupCheck> taskGroupCheck, Set<WeekDay> days) {

}
