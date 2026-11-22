package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType;

/**
 * One thing with its notes on the page: a timeline, oldest first.
 *
 * @param id         the thing's id; the category's for notes moved there from a deleted thing;
 *                   null for LOOSE
 * @param name       the live name while the thing exists, else the name the notes kept
 * @param iconId     the thing's icon while it exists, else null
 * @param formerType what the thing was, on notes of a thing that was deleted; null otherwise
 */
public record SecondBrainMemorySubjectDTO(
    SecondBrainSubjectType type,
    UUID id,
    String name,
    String iconId,
    SecondBrainSubjectType formerType,
    List<SecondBrainNoteDTO> notes
) {}
