package beyou.beyouapp.backend.domain.task.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateTaskRequestDTO(
    @NotEmpty @Size(min = 2, max = 256, message = "Category need a minimum of 2 characters")
    String name, 
    String description,
    @NotBlank
    String iconId,
    Integer importance,
    Integer difficulty,
    List<UUID> categoriesId
) {
    
}
