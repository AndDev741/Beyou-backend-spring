package beyou.beyouapp.backend.domain.notebook.ai.draft.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.ai.draft.RoadmapDraftStatus;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftRequestDTO;

/**
 * A whole draft, enough to put the dialog back exactly where the person left it.
 *
 * @param request   what was asked for, to refill the form
 * @param result    the drafted nodes; null until the first call ends. While a redraft runs it
 *                  is still the previous result, which the dialog shows dimmed
 * @param choices   one per node of {@code result}, or null when the person has not changed any
 * @param errorKey  why a FAILED draft failed (AI_UNAVAILABLE, NOTEBOOK_DRAFT_INTERRUPTED)
 * @param startedAt when the current or last model call began, for the dialog's timer
 */
public record RoadmapDraftRecordDTO(
        UUID id,
        String title,
        RoadmapDraftStatus status,
        RoadmapDraftRequestDTO request,
        RoadmapDraftDTO result,
        List<DraftChoiceDTO> choices,
        String errorKey,
        Instant startedAt,
        Instant createdAt,
        Instant updatedAt) {
}
