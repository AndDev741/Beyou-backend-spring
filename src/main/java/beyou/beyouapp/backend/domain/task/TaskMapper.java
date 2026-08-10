package beyou.beyouapp.backend.domain.task;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.dto.CategoryMiniDTO;
import beyou.beyouapp.backend.domain.checkday.CheckProgressCalculator;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.TaskResponseDTO;
import beyou.beyouapp.backend.user.User;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class TaskMapper {

    public Task toEntity(CreateTaskRequestDTO dto, List<Category> categories, User user) {
        List<Category> safeCategories = categories != null ? categories : Collections.emptyList();
        return new Task(dto, Optional.of(safeCategories), user);
    }

    public void updateEntity(Task task, EditTaskRequestDTO dto, List<Category> categories) {
        task.setName(dto.name());
        task.setDescription(dto.description());
        task.setIconId(dto.iconId());
        task.setImportance(dto.importance());
        task.setDificulty(dto.difficulty());
        task.setCategories(new ArrayList<>(categories != null ? categories : Collections.emptyList()));
        task.setOneTimeTask(dto.oneTimeTask());
    }

    public TaskResponseDTO toResponseDTO(Task task) {
        Map<UUID, CategoryMiniDTO> categories = task.getCategories()
            != null 
            ?
            task.getCategories().stream()
                .collect(Collectors.toMap(
                    Category::getId,
                    category -> new CategoryMiniDTO(
                        category.getName(),
                        category.getIconId()
                    ),
                    // A task may carry the same category twice (duplicate join
                    // row); collapse instead of throwing on the key.
                    (existing, duplicate) -> existing
                ))
            : Map.of();

        LocalDate createdAt = task.getCreatedAt() != null ? task.getCreatedAt().toLocalDate() : null;
        LocalDate updatedAt = task.getUpdatedAt() != null ? task.getUpdatedAt().toLocalDate() : null;

        // R2 — same shape and the same null guard as HabitMapper: Hibernate hands back a
        // null embeddable for a task row written before V13, when every check_ column of
        // it was null.
        CheckProgress checkProgress = task.getCheckProgress();
        // R15 — dormancy needs a today, and the only correct today is the owner's.
        // task.getUser() is an eager @ManyToOne, already loaded, so this costs no statement.
        boolean streakDormant = CheckProgressCalculator.isDormant(
                checkProgress, UserDateResolver.today(task.getUser()));

        return new TaskResponseDTO(
                task.getId(),
                task.getName(),
                task.getDescription(),
                task.getIconId(),
                task.getImportance(),
                task.getDificulty(),
                categories,
                task.isOneTimeTask(),
                checkProgress != null ? checkProgress.getCurrentStreak() : 0,
                checkProgress != null ? checkProgress.getBestStreak() : 0,
                checkProgress != null ? checkProgress.getTotalCheckIns() : 0,
                checkProgress != null ? checkProgress.getFirstCheckInDate() : null,
                streakDormant,
                task.getMarkedToDelete(),
                createdAt,
                updatedAt
        );
    }
}
