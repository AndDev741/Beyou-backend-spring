package beyou.beyouapp.backend.domain.ai.dto;

import java.util.UUID;

import jakarta.validation.Valid;

/** Exactly one of existingHabitId / newHabit must be set (enforced by AiDraftValidator). */
public record DraftHabitItemDTO(
        UUID existingHabitId,
        @Valid DraftNewHabitDTO newHabit,
        String startTime,
        String endTime) {
}
