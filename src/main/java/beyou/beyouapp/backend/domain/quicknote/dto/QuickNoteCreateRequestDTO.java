package beyou.beyouapp.backend.domain.quicknote.dto;

import beyou.beyouapp.backend.domain.quicknote.QuickNote;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteColor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A new note. Only {@code text} is required.
 *
 * <p>A null {@code color} is yellow. {@code x} and {@code y} are the spot it was dropped on (a
 * double-click on the web board) and count only together: with one of them missing the server
 * places the note in the first free grid cell, the same as with neither, so a client that lost
 * half a coordinate does not pin a note to an edge.
 */
public record QuickNoteCreateRequestDTO(
    @NotBlank
    @Size(max = QuickNote.TEXT_MAX)
    String text,

    QuickNoteColor color,

    @Min(-QuickNote.COORD_LIMIT)
    @Max(QuickNote.COORD_LIMIT)
    Integer x,

    @Min(-QuickNote.COORD_LIMIT)
    @Max(QuickNote.COORD_LIMIT)
    Integer y
) {}
