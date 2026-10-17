package beyou.beyouapp.backend.domain.quicknote.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.quicknote.QuickNote;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Positions for one or more of the caller's active notes: a drag, or several. All or nothing:
 * an id that is not one of the caller's active notes refuses the whole save with 404
 * {@code QUICK_NOTE_NOT_FOUND}, so a board never ends up half moved. The same id twice is 400.
 *
 * <p>Refused rather than skipped, unlike the notebook board's layout, because a skipped id here
 * would hide the one case that matters: a note archived on another device while this one was
 * dragging it. The client reloads the board on the 404.
 */
public record QuickNoteLayoutRequestDTO(
    @NotNull
    @Size(min = 1, max = QuickNote.MAX_ACTIVE)
    List<@NotNull @Valid QuickNotePositionDTO> positions
) {}
