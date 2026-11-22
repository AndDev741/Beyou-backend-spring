package beyou.beyouapp.backend.domain.secondbrain.dto;

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainNote;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A note's corrected words. The text is stored trimmed; the origin stays what it was. */
public record SecondBrainNoteRequestDTO(
    @NotBlank
    @Size(max = SecondBrainNote.TEXT_MAX)
    String text
) {}
