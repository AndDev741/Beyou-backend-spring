package beyou.beyouapp.backend.domain.notebook.ai.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A node of a draft as the client sends it back: to revise the draft, or to create the topic.
 *
 * @param linkPageId an existing page to link instead of creating a new one ("Link it")
 */
public record DraftNodeInputDTO(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 500) String why,
        @Size(max = 30) List<@NotBlank @Size(max = 255) String> subtopics,
        Integer estimatedHours,
        UUID linkPageId) {
}
