package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Study done outside the app.
 *
 * @param studiedOn the day it happened, within the last 30 days of the owner's own calendar and
 *                  not in the future. It places the session in the history only: the minutes go
 *                  into the current lap whatever the date
 */
public record SessionRequestDTO(
        @NotNull UUID topicId,
        UUID pageId,
        @NotNull @Min(1) @Max(600) Integer minutes,
        @NotNull LocalDate studiedOn,
        @Size(max = 200) String note) {
}
