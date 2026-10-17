package beyou.beyouapp.backend.domain.quicknote.dto;

import java.util.UUID;

/**
 * The answer to "make it a task": the note as it now stands (archived, pointing at the task) and
 * the new task's id. The task itself is read through the task endpoints, which is where both
 * clients already keep their task lists.
 */
public record QuickNoteTaskConversionDTO(
    QuickNoteResponseDTO note,
    UUID taskId
) {}
