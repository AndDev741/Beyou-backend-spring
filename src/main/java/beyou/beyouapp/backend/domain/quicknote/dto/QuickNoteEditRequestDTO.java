package beyou.beyouapp.backend.domain.quicknote.dto;

import beyou.beyouapp.backend.domain.quicknote.QuickNote;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteColor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The edit body: the words and the colour, both required. The spot is not here because moving
 * is the layout endpoint's job, and an edit sheet that sent a stale position back would undo a
 * drag made on another device.
 */
public record QuickNoteEditRequestDTO(
    @NotBlank
    @Size(max = QuickNote.TEXT_MAX)
    String text,

    @NotNull
    QuickNoteColor color
) {}
