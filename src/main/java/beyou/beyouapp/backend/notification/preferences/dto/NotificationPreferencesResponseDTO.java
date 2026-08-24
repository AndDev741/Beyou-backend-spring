package beyou.beyouapp.backend.notification.preferences.dto;

/**
 * What the settings screen reads.
 *
 * <p>Carries the switch and nothing else. The unsubscribe token is deliberately absent:
 * it is a capability that works without a session, and a client that already holds a
 * session has no use for it — echoing it into a response would put it in browser
 * history, logs and any error report that captures a payload, for no gain.
 */
public record NotificationPreferencesResponseDTO(boolean engagementEmail) {
}
