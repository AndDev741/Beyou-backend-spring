package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainNoteOrigin;

/**
 * One memory note, as the page, the assistant and an answer's result see it.
 *
 * @param subjectName the thing's name when the note was written, or its last name once gone
 */
public record SecondBrainNoteDTO(
    UUID id,
    String text,
    SecondBrainNoteOrigin origin,
    String subjectName,
    Instant createdAt,
    Instant updatedAt
) {}
