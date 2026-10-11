package beyou.beyouapp.backend.domain.calendar.dto;

import java.util.UUID;

/**
 * A routine on one day of the calendar.
 *
 * <p>For a {@link CalendarRoutineSource#SNAPSHOT} entry the name and icon are the ones the
 * routine had on that day, and the counts are filled: {@code checkedItems} of
 * {@code totalItems}, skips counting as not checked. For a {@link CalendarRoutineSource#SCHEDULE}
 * entry the counts are null and {@code completed} is false.
 */
public record CalendarRoutineDTO(
    UUID routineId,
    String name,
    String iconId,
    CalendarRoutineSource source,
    Integer totalItems,
    Integer checkedItems,
    boolean completed
) {}
