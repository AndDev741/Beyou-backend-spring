package beyou.beyouapp.backend.domain.calendar;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When a calendar task's reminder fires. The one place that answers it.
 *
 * <p>The response DTO's {@code notifyAt}, the upcoming-reminders endpoint and the reminder
 * mail job all call {@link #instantOf}. No client recomputes it and no column stores it: the
 * web hub, the phone and the mail must agree on the minute, and a stored value would go
 * stale the day the owner changed timezone or default time.
 *
 * <p>Static and pure, like {@code CheckXpCalculator} and {@code UserDateResolver}: no state,
 * nothing to inject, and every rule below is testable with plain values.
 */
public final class CalendarReminderTime {

    private CalendarReminderTime() {}

    /** The default reminder time out of the box, for an account that never chose one. */
    public static final LocalTime DEFAULT_TIME = LocalTime.of(9, 0);

    private static final int MINUTES_PER_DAY = 24 * 60;

    /**
     * The reminder instant: the task's date at its time (or at {@code defaultTime} when the
     * task has none), minus the advance notice, in {@code zone}.
     *
     * <p>Whole days of advance are taken off the calendar and the rest off the clock. "A day
     * before" a 09:00 task means 09:00 the day before, also when a daylight-saving change
     * falls in between; taken as 1440 minutes it would fire at 08:00 or 10:00 that day. "Two
     * hours before" means two real hours, so it is taken off the instant, where a wall-clock
     * subtraction across the autumn change would land an hour off.
     *
     * <p>A local time that does not exist on the day (inside a spring-forward gap) moves
     * forward by the length of the gap, which is what {@link ZonedDateTime#of} does and what
     * an alarm clock does too.
     *
     * @param time        the task's time, or null for an untimed task
     * @param defaultTime the owner's default reminder time; null falls back to
     *                    {@link #DEFAULT_TIME}
     */
    public static Instant instantOf(LocalDate date, LocalTime time, int advanceMinutes,
                                    ZoneId zone, LocalTime defaultTime) {
        LocalTime at = time != null ? time : (defaultTime != null ? defaultTime : DEFAULT_TIME);
        int advance = Math.max(0, advanceMinutes);

        ZonedDateTime due = ZonedDateTime.of(date, at, zone);
        // minusDays works on the local date-time and re-resolves the offset; minus(Duration)
        // works on the instant. That split is the rule in the javadoc above.
        return due.minusDays(advance / MINUTES_PER_DAY)
                .toInstant()
                .minus(Duration.ofMinutes(advance % MINUTES_PER_DAY));
    }
}
