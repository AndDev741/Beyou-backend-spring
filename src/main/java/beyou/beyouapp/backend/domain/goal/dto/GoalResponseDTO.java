package beyou.beyouapp.backend.domain.goal.dto;

import beyou.beyouapp.backend.domain.category.dto.CategoryMiniDTO;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record GoalResponseDTO(
        UUID id,
        String name,
        String iconId,
        String description,
        Double targetValue,
        String unit,
        Double currentValue,
        Boolean complete,
        Map<UUID,CategoryMiniDTO> categories,
        String motivation,
        LocalDate startDate,
        LocalDate endDate,
        double xpReward,
        GoalStatus status,
        GoalTerm term,
        LocalDate completeDate
) {
}
