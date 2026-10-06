package beyou.beyouapp.backend.domain.notebook.ai.draft.dto;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.ai.draft.RoadmapDraftStatus;

/** A draft as the notebook home lists it. */
public record RoadmapDraftSummaryDTO(
        UUID id,
        String title,
        RoadmapDraftStatus status,
        int nodeCount,
        String errorKey,
        Instant startedAt,
        Instant updatedAt) {
}
