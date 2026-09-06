package beyou.beyouapp.backend.domain.mood.dto;

import beyou.beyouapp.backend.domain.mood.MoodEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The day's full state: how it felt, and what was written about it.
 *
 * <p>The date is not in the body. It is the path variable, so {@code PUT /mood/2026-09-06}
 * is addressable and idempotent — sending the same body twice leaves one row.
 *
 * <p>A PUT replaces the entry, so a null {@code note} clears whatever was written before.
 * That is why the widget does not use this route: someone who journals in the morning and
 * taps a face on the dashboard at night must not lose the morning's writing to a request
 * that never carried it. Setting only the level is {@code PATCH}, with
 * {@link SetMoodLevelDTO}, which cannot touch the note at all. This route belongs to the
 * page's Save button, where the textarea on screen really is the day's final text.
 */
public record UpsertMoodEntryDTO(
    @NotNull
    @Min(MoodEntry.MIN_MOOD)
    @Max(MoodEntry.MAX_MOOD)
    Integer mood,

    @Size(max = MoodEntry.MAX_NOTE_LENGTH)
    String note
) {}
