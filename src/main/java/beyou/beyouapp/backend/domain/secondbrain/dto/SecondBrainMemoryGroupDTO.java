package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.util.List;
import java.util.UUID;

/**
 * The memory block's groups: one per category that has something under it, then one with no
 * category for routines and for notes whose thing is gone. Built and ordered on the server
 * ({@code SecondBrainNotes#memory}), so the two clients cannot group the same notes differently.
 *
 * @param categoryId null for the group of routines and loose notes, which comes last
 */
public record SecondBrainMemoryGroupDTO(
    UUID categoryId,
    String categoryName,
    String categoryIconId,
    List<SecondBrainMemorySubjectDTO> subjects
) {}
