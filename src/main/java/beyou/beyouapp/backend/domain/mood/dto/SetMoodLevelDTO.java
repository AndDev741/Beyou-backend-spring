package beyou.beyouapp.backend.domain.mood.dto;

import beyou.beyouapp.backend.domain.mood.MoodEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Just the level, for the one-tap path.
 *
 * <p>The dashboard widget and the page's row of faces both send this. It exists as its own
 * route so that a client which has never loaded the day's note cannot delete it: there is
 * no field here to carry a note in, so no request shaped like this can clear one.
 * Replacing the whole entry, note included, is {@code PUT} with {@link UpsertMoodEntryDTO}.
 */
public record SetMoodLevelDTO(
    @NotNull
    @Min(MoodEntry.MIN_MOOD)
    @Max(MoodEntry.MAX_MOOD)
    Integer mood
) {}
