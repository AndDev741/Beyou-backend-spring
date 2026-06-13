package beyou.beyouapp.backend.domain.ai.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Field name "dificulty" matches the existing habit wire format
 * (CreateHabitDTO) — do not fix the typo here without a coordinated rename.
 * categoryRefs entries are an existing category UUID string OR a tempKey.
 */
public record DraftNewHabitDTO(
        @NotBlank @Size(min = 2, max = 256) String name,
        @Size(max = 1000) String description,
        @Size(max = 500) String motivationalPhrase,
        String iconId,
        Integer importance,
        Integer dificulty,
        List<String> categoryRefs) {
}
