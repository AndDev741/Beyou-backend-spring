package beyou.beyouapp.backend.domain.quicknote.dto;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.domain.quicknote.QuickNoteColor;

/**
 * One note as both clients and the export see it. {@code archivedAt} null means it is on the
 * board; {@code convertedTaskId} set means it became that task (and is archived).
 */
public record QuickNoteResponseDTO(
    UUID id,
    String text,
    QuickNoteColor color,
    int x,
    int y,
    int z,
    Instant archivedAt,
    UUID convertedTaskId,
    Instant createdAt,
    Instant updatedAt
) {}
