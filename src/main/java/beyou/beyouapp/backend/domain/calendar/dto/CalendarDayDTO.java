package beyou.beyouapp.backend.domain.calendar.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything one day of the calendar shows.
 *
 * <p>{@code tasks}: timed tasks first, by time, then untimed ones in the order they were
 * created. {@code mood} is null when the day has no entry.
 */
public record CalendarDayDTO(
    LocalDate date,
    List<CalendarTaskResponseDTO> tasks,
    List<CalendarRoutineDTO> routines,
    List<CalendarGoalDTO> goals,
    CalendarMoodDTO mood
) {}
