package beyou.beyouapp.backend.domain.notebook.ai.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "New topic with AI". Stateless like the onboarding: a revision sends the previous draft back
 * with {@code changeRequest}, and nothing is stored until the topic is created.
 *
 * @param references links or names the person wants the roadmap based on. Passed to the model as
 *                   text; nothing is fetched.
 */
public record RoadmapDraftRequestDTO(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 600) String why,
        StudyLevel level,
        @Min(1) @Max(80) Integer hoursPerWeek,
        UUID goalId,
        @Size(max = 5) List<@Size(max = 500) String> references,
        @Size(max = 500) String changeRequest,
        @Size(max = 20) List<@Valid DraftNodeInputDTO> previous) {
}
