package beyou.beyouapp.backend.domain.secondbrain.dto;

import beyou.beyouapp.backend.domain.secondbrain.OldNote;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One sentence of the old hidden note, exactly as the page received it in {@code oldNote}. */
public record SecondBrainOldNoteRequestDTO(
    @NotBlank
    @Size(max = OldNote.LINE_MAX)
    String text
) {}
