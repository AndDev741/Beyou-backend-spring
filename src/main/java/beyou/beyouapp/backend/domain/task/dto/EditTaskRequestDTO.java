package beyou.beyouapp.backend.domain.task.dto;

import java.util.List;
import java.util.UUID;

public record EditTaskRequestDTO(
    UUID taskId,
    String name, 
    String description,
    String iconId,
    Integer importance,
    Integer difficulty,
    List<UUID> categoriesId,
    boolean oneTimeTask) {
    
}
