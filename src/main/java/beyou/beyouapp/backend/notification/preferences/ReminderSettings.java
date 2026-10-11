package beyou.beyouapp.backend.notification.preferences;

import java.time.LocalTime;

import beyou.beyouapp.backend.domain.calendar.CalendarReminderTime;

/**
 * How calendar reminders reach one account, read without creating its preferences row.
 *
 * <p>The calendar reads this on every range request and the reminder job for every account
 * it considers, inside read-only transactions where
 * {@link NotificationPreferencesService#getOrCreate} could not insert. A missing row means
 * {@link #DEFAULTS}, the same rule {@code getOrCreate} applies, kept next to it in
 * {@link NotificationPreferencesService#reminderSettings}.
 */
public record ReminderSettings(boolean email, boolean web, LocalTime defaultTime) {

    /** Everything on, 09:00. Mirrors the column defaults in V40. */
    public static final ReminderSettings DEFAULTS =
            new ReminderSettings(true, true, CalendarReminderTime.DEFAULT_TIME);

    static ReminderSettings of(NotificationPreferences preferences) {
        return new ReminderSettings(
                preferences.isReminderEmail(),
                preferences.isReminderWeb(),
                preferences.getReminderDefaultTime());
    }
}
