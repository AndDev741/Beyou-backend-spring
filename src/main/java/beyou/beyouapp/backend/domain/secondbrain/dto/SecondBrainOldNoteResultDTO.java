package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.util.List;

/**
 * What keeping or discarding one sentence of the old note left.
 *
 * @param lines the sentences still waiting, in order; empty once the note is gone
 * @param fact  the profile line a kept sentence became, or null for a discard
 */
public record SecondBrainOldNoteResultDTO(List<String> lines, SecondBrainFactDTO fact) {}
