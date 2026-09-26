package beyou.beyouapp.backend.domain.category;

import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.user.User;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class CategoryMapper {

    public Category toEntity(CategoryRequestDTO dto, User user, XpByLevel actualLevelXp, XpByLevel nextLevelXp) {
        Category category = new Category(dto, user);
        XpProgress xpProgress = category.getXpProgress() != null ? category.getXpProgress() : new XpProgress();
        if (actualLevelXp != null) {
            xpProgress.setActualLevelXp(actualLevelXp.getXp());
        }
        if (nextLevelXp != null) {
            xpProgress.setNextLevelXp(nextLevelXp.getXp());
        }
        category.setXpProgress(xpProgress);
        return category;
    }

    public void updateEntity(Category category, CategoryEditRequestDTO dto) {
        category.setName(dto.name());
        category.setDescription(dto.description());
        category.setIconId(dto.icon());
    }

    public CategoryResponseDTO toResponseDTO(Category category) {
        Map<UUID, String> habits = category.getHabits() != null
                ? category.getHabits().stream().collect(Collectors.toMap(Habit::getId, Habit::getName))
                : Map.of();

        Map<UUID, String> tasks = category.getTasks() != null
                ? category.getTasks().stream().collect(Collectors.toMap(Task::getId, Task::getName))
                : Map.of();

        // Archived goals stay linked to the category (restoring one must bring its categories
        // back with it) but are not listed on the card, which shows what the category is working
        // towards now.
        Map<UUID, String> goals = category.getGoals() != null
                ? category.getGoals().stream()
                        .filter(goal -> goal.getArchivedAt() == null)
                        .collect(Collectors.toMap(Goal::getId, Goal::getName))
                : Map.of();

        XpProgress xpProgress = category.getXpProgress() != null ? category.getXpProgress() : new XpProgress();
        Date createdAt = category.getCreatedAt();

        return new CategoryResponseDTO(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getIconId(),
                habits,
                tasks,
                goals,
                xpProgress.getXp(),
                xpProgress.getNextLevelXp(),
                xpProgress.getActualLevelXp(),
                xpProgress.getLevel(),
                createdAt
        );
    }
}
