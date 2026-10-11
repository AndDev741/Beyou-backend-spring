package beyou.beyouapp.backend.domain.calendar.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import beyou.beyouapp.backend.domain.calendar.CalendarTask;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The create and edit body. A PUT sends the whole task, so a null field here means "none",
 * never "unchanged": a null {@code time} makes the task untimed, a null {@code note} clears it.
 *
 * <p>{@code time} is read as {@code HH:mm} (seconds are accepted and dropped). A null
 * {@code advanceMinutes} means no advance notice; the presets the clients offer are 0, 15, 30,
 * 60, 120 and 1440, and the server takes anything up to a week.
 */
public record CalendarTaskRequestDTO(
    @NotBlank
    @Size(min = CalendarTask.MIN_TITLE_LENGTH, max = CalendarTask.MAX_TITLE_LENGTH)
    String title,

    @Size(max = CalendarTask.MAX_NOTE_LENGTH)
    String note,

    @NotNull
    LocalDate date,

    LocalTime time,

    @Min(0)
    @Max(CalendarTask.MAX_ADVANCE_MINUTES)
    Integer advanceMinutes
) {}
