package beyou.beyouapp.backend.domain.quicknote.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.quicknote.QuickNote;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Where one note now sits, and its stacking order. Saved exactly as sent. */
public record QuickNotePositionDTO(
    @NotNull
    UUID id,

    @NotNull
    @Min(-QuickNote.COORD_LIMIT)
    @Max(QuickNote.COORD_LIMIT)
    Integer x,

    @NotNull
    @Min(-QuickNote.COORD_LIMIT)
    @Max(QuickNote.COORD_LIMIT)
    Integer y,

    @NotNull
    @Min(0)
    Integer z
) {}
