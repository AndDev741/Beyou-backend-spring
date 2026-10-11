package beyou.beyouapp.backend.unit.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.calendar.CalendarReminderTime;

/**
 * The reminder instant, the one number the browser, the phone and the mail job all act on.
 *
 * <p>Fixtures are chosen so a wrong zone cannot pass by accident. Sao Paulo is three hours from
 * UTC and has had no daylight saving since 2019, so a server in UTC, in Lisbon or in New York
 * gets a different answer from a correct one. The New York cases sit on the 2026 autumn change
 * (1 November, 02:00 EDT back to 01:00 EST), where "a day" and "1440 minutes" stop meaning the
 * same thing.
 */
class CalendarReminderTimeUnitTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime NINE = LocalTime.of(9, 0);

    @Test
    @DisplayName("AE1: an untimed task a day ahead reminds at the default time the day before, in the owner's zone")
    void untimedTaskUsesTheDefaultTimeInTheOwnersZone() {
        Instant instant = CalendarReminderTime.instantOf(
                LocalDate.of(2026, 10, 14), null, 1440, SAO_PAULO, NINE);

        assertThat(instant).isEqualTo(Instant.parse("2026-10-13T12:00:00Z"));
    }

    @Test
    @DisplayName("the contract's example: 10:00 with an hour's notice in Sao Paulo is 12:00 UTC")
    void timedTaskSubtractsTheAdvance() {
        Instant instant = CalendarReminderTime.instantOf(
                LocalDate.of(2026, 10, 14), LocalTime.of(10, 0), 60, SAO_PAULO, NINE);

        assertThat(instant).isEqualTo(Instant.parse("2026-10-14T12:00:00Z"));
    }

    @Test
    @DisplayName("a task's own time wins over the default")
    void ownTimeWinsOverTheDefault() {
        Instant instant = CalendarReminderTime.instantOf(
                LocalDate.of(2026, 10, 14), LocalTime.of(18, 30), 0, SAO_PAULO, LocalTime.of(7, 0));

        assertThat(instant).isEqualTo(Instant.parse("2026-10-14T21:30:00Z"));
    }

    @Test
    @DisplayName("an account with no default time stored falls back to 09:00")
    void missingDefaultFallsBackToNine() {
        Instant instant = CalendarReminderTime.instantOf(
                LocalDate.of(2026, 10, 14), null, 0, SAO_PAULO, null);

        assertThat(instant).isEqualTo(Instant.parse("2026-10-14T12:00:00Z"));
    }

    /**
     * 09:00 on 1 November is EST (14:00Z); 09:00 the day before is still EDT (13:00Z). Taken as
     * 1440 minutes off the instant, "a day before" would fire at 10:00 local on the 31st.
     */
    @Test
    @DisplayName("a day of notice across the DST change keeps the wall-clock time")
    void wholeDaysOfNoticeKeepTheWallClockAcrossDst() {
        Instant instant = CalendarReminderTime.instantOf(
                LocalDate.of(2026, 11, 1), LocalTime.of(9, 0), 1440, NEW_YORK, NINE);

        assertThat(instant).isEqualTo(Instant.parse("2026-10-31T13:00:00Z"));
    }

    /**
     * 03:00 EST on 1 November is 08:00Z. Two real hours before is 06:00Z, which is 01:00 EST.
     * Subtracting on the wall clock lands on 01:00 too, but on the first of the two 01:00s,
     * which is EDT and an hour earlier (05:00Z).
     */
    @Test
    @DisplayName("hours of notice across the DST change are real hours")
    void minutesOfNoticeAreRealMinutesAcrossDst() {
        Instant instant = CalendarReminderTime.instantOf(
                LocalDate.of(2026, 11, 1), LocalTime.of(3, 0), 120, NEW_YORK, NINE);

        assertThat(instant).isEqualTo(Instant.parse("2026-11-01T06:00:00Z"));
    }

    /** 02:30 does not exist on 8 March 2026 in New York; it resolves to 03:30 EDT (07:30Z). */
    @Test
    @DisplayName("a time inside the spring-forward gap moves forward instead of failing")
    void timeInsideTheGapMovesForward() {
        Instant instant = CalendarReminderTime.instantOf(
                LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), 0, NEW_YORK, NINE);

        assertThat(instant).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
    }

    @Test
    @DisplayName("a negative advance is treated as none")
    void negativeAdvanceIsNone() {
        Instant instant = CalendarReminderTime.instantOf(
                LocalDate.of(2026, 10, 14), LocalTime.of(10, 0), -30, SAO_PAULO, NINE);

        assertThat(instant).isEqualTo(Instant.parse("2026-10-14T13:00:00Z"));
    }
}
