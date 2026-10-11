package beyou.beyouapp.backend.domain.calendar.dto;

/**
 * Where a day's routine entry came from, which decides whether it carries counts.
 *
 * <p>{@link #SNAPSHOT}: the day's frozen record, so the counts say how it went. Past days and
 * today once its record exists. {@link #SCHEDULE}: the weekday schedule, so it only says what
 * the day holds. Today before its record, and every future day.
 */
public enum CalendarRoutineSource {
    SNAPSHOT,
    SCHEDULE
}
