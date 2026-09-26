package beyou.beyouapp.backend.domain.goal.dto;

import beyou.beyouapp.backend.domain.category.dto.CategoryMiniDTO;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;

import java.time.Instant;
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
        LocalDate completeDate,
        UUID parentId,
        Instant archivedAt
) {
    /**
     * Every goal built before archiving existed, which is most test fixtures. Safe as an
     * overload only because this record is built by the server and never read from a request
     * body; Jackson never has to choose between the two constructors.
     */
    public GoalResponseDTO(UUID id, String name, String iconId, String description, Double targetValue,
            String unit, Double currentValue, Boolean complete, Map<UUID, CategoryMiniDTO> categories,
            String motivation, LocalDate startDate, LocalDate endDate, double xpReward, GoalStatus status,
            GoalTerm term, LocalDate completeDate, UUID parentId) {
        this(id, name, iconId, description, targetValue, unit, currentValue, complete, categories, motivation,
                startDate, endDate, xpReward, status, term, completeDate, parentId, null);
    }
}
