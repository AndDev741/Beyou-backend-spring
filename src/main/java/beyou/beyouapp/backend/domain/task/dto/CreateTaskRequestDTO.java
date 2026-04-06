package beyou.beyouapp.backend.domain.task.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTaskRequestDTO(
    @NotEmpty @Size(min = 2, max = 256, message = "Task needs a minimum of 2 characters")
    String name,
    String description,
    @NotBlank
    String iconId,
    @NotNull @Min(1) @Max(5) Integer importance,
    @NotNull @Min(1) @Max(5) Integer difficulty,
    List<UUID> categoriesId,
    boolean oneTimeTask
) {
    
}
