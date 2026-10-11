package beyou.beyouapp.backend.notification.preferences.dto;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import beyou.beyouapp.backend.notification.preferences.NotificationPreferences;

/**
 * What the settings screen reads.
 *
 * <p>Carries the switches and nothing else. The unsubscribe token is deliberately absent:
 * it is a capability that works without a session, and a client that already holds a
 * session has no use for it — echoing it into a response would put it in browser
 * history, logs and any error report that captures a payload, for no gain.
 *
 * <p>The three reminder fields are independent of {@code engagementEmail}: one is mail the
 * product decides to send, the others are reminders the user set themselves.
 */
public record NotificationPreferencesResponseDTO(
        boolean engagementEmail,
        boolean reminderEmail,
        boolean reminderWeb,
        @JsonFormat(pattern = "HH:mm")
        LocalTime reminderDefaultTime) {

    public static NotificationPreferencesResponseDTO from(NotificationPreferences preferences) {
        return new NotificationPreferencesResponseDTO(
                preferences.isEngagementEmail(),
                preferences.isReminderEmail(),
                preferences.isReminderWeb(),
                preferences.getReminderDefaultTime());
    }
}
