package beyou.beyouapp.backend.domain.task;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.dto.CategoryMiniDTO;
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
                    )
                ))
            : Map.of();

        LocalDate createdAt = task.getCreatedAt() != null ? task.getCreatedAt().toLocalDate() : null;
        LocalDate updatedAt = task.getUpdatedAt() != null ? task.getUpdatedAt().toLocalDate() : null;

        return new TaskResponseDTO(
                task.getId(),
                task.getName(),
                task.getDescription(),
                task.getIconId(),
                task.getImportance(),
                task.getDificulty(),
                categories,
                task.isOneTimeTask(),
                task.getMarkedToDelete(),
                createdAt,
                updatedAt
        );
    }
}
