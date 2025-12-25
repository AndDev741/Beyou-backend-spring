package beyou.beyouapp.backend.domain.goal;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.dto.CategoryMiniDTO;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import beyou.beyouapp.backend.user.User;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class GoalMapper {

    public Goal toEntity(CreateGoalRequestDTO dto, List<Category> categories, User user) {
        List<Category> safeCategories = categories != null ? categories : Collections.emptyList();
        return new Goal(dto, safeCategories, user);
    }

    public void updateEntity(Goal goal, EditGoalRequestDTO dto, List<Category> categories) {
        goal.setName(dto.name());
        goal.setIconId(dto.iconId());
        goal.setDescription(dto.description());
        goal.setTargetValue(dto.targetValue());
        goal.setUnit(dto.unit());
        goal.setCurrentValue(dto.currentValue());
        goal.setComplete(dto.complete());
        goal.setCategories(new ArrayList<>(categories != null ? categories : Collections.emptyList()));
        goal.setMotivation(dto.motivation());
        goal.setStartDate(dto.startDate());
        goal.setEndDate(dto.endDate());
        goal.setStatus(dto.status());
        goal.setTerm(dto.term());
    }

    public GoalResponseDTO toResponseDTO(Goal goal) {
        Map<UUID, CategoryMiniDTO> categories =
        goal.getCategories() != null
                ? goal.getCategories().stream()
                    .collect(Collectors.toMap(
                        Category::getId,
                        category -> new CategoryMiniDTO(
                            category.getName(),
                            category.getIconId()
                        )
                    ))
                : Map.of();

        return new GoalResponseDTO(
                goal.getId(),
                goal.getName(),
                goal.getIconId(),
                goal.getDescription(),
                goal.getTargetValue(),
                goal.getUnit(),
                goal.getCurrentValue(),
                goal.getComplete(),
                categories,
                goal.getMotivation(),
                goal.getStartDate(),
                goal.getEndDate(),
                goal.getXpReward(),
                goal.getStatus(),
                goal.getTerm(),
                goal.getCompleteDate()
        );
    }
}
