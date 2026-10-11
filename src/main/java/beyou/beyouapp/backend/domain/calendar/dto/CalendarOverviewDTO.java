package beyou.beyouapp.backend.domain.calendar.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * A visible range of the calendar in one response: one entry per date in
 * {@code [from, to]}, ascending, empty days included so a client never has to fill gaps.
 *
 * <p>{@code today} is the owner's today in their own timezone, which is the line between a
 * routine's snapshot and its schedule, and what the clients highlight.
 */
public record CalendarOverviewDTO(
    LocalDate from,
    LocalDate to,
    LocalDate today,
    List<CalendarDayDTO> days
) {}
