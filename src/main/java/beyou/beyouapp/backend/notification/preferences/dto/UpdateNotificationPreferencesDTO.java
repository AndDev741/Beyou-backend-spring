package beyou.beyouapp.backend.notification.preferences.dto;

import java.time.LocalTime;

/**
 * The settings-screen write. Partial: a null field leaves that switch as it is.
 *
 * <p>Boxed types, so a field the client did not send reads as null rather than as
 * {@code false}. That is what lets a client that only knows about {@code engagementEmail}
 * keep sending {@code {"engagementEmail": false}} without turning reminders off, and lets
 * the Reminders section send only its own fields. A body with nothing in it changes nothing.
 *
 * <p>{@code reminderDefaultTime} is read as {@code HH:mm}; seconds are accepted and dropped.
 */
public record UpdateNotificationPreferencesDTO(
        Boolean engagementEmail,
        Boolean reminderEmail,
        Boolean reminderWeb,
        LocalTime reminderDefaultTime) {
}
