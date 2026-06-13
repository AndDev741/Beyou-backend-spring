package beyou.beyouapp.backend.domain.ai.dto;

import java.util.List;
import java.util.Set;

import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * AI-generated routine draft. Travels in three places: the generate response,
 * the previousDraft of a re-generate request, and the confirm request body.
 * All times are "HH:mm" strings. Items are a reference to an existing entity
 * XOR a new-item proposal — AiDraftValidator enforces this.
 */
public record RoutineDraftDTO(
        @NotBlank @Size(min = 2, max = 256) String name,
        String iconId,
        @Valid List<DraftNewCategoryDTO> newCategories,
        @NotEmpty @Valid List<DraftSectionDTO> sections,
        Set<WeekDay> scheduleDays) {
}
