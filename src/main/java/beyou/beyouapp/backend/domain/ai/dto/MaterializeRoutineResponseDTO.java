package beyou.beyouapp.backend.domain.ai.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;

/**
 * Result of materializing an AI draft: every new category/habit/task has been
 * created and all items are now plain entity references, shaped to drop
 * straight into the manual routine form (habitGroup/taskGroup naming matches
 * the frontend RoutineSection type). The new* id lists let the UI badge
 * freshly-created items. The routine itself is NOT created here — the user
 * finishes through the normal create/edit form flow.
 */
public record MaterializeRoutineResponseDTO(
        String name,
        String iconId,
        Set<WeekDay> scheduleDays,
        List<SectionDTO> sections,
        List<UUID> newCategoryIds,
        List<UUID> newHabitIds,
        List<UUID> newTaskIds) {

    public record SectionDTO(
            String name,
            String iconId,
            String startTime,
            String endTime,
            List<HabitGroupRefDTO> habitGroup,
            List<TaskGroupRefDTO> taskGroup) {
    }

    public record HabitGroupRefDTO(UUID habitId, String startTime, String endTime) {
    }

    public record TaskGroupRefDTO(UUID taskId, String startTime, String endTime) {
    }
}
