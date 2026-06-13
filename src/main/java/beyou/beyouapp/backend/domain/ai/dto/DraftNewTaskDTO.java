package beyou.beyouapp.backend.domain.ai.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Task wire format spells "difficulty" correctly (matches CreateTaskRequestDTO). */
public record DraftNewTaskDTO(
        @NotBlank @Size(min = 2, max = 256) String name,
        @Size(max = 1000) String description,
        String iconId,
        Integer importance,
        Integer difficulty,
        List<String> categoryRefs,
        boolean oneTimeTask) {
}
