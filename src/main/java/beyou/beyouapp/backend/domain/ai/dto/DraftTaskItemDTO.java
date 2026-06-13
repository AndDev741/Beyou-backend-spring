package beyou.beyouapp.backend.domain.ai.dto;

import java.util.UUID;

import jakarta.validation.Valid;

/** Exactly one of existingTaskId / newTask must be set (enforced by AiDraftValidator). */
public record DraftTaskItemDTO(
        UUID existingTaskId,
        @Valid DraftNewTaskDTO newTask,
        String startTime,
        String endTime) {
}
