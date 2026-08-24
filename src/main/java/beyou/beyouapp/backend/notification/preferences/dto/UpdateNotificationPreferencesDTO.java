package beyou.beyouapp.backend.notification.preferences.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The settings-screen write.
 *
 * <p>A boxed {@code Boolean} with {@code @NotNull} rather than a primitive: a primitive
 * would silently read a missing field as {@code false}, so a malformed request would
 * turn the switch off instead of being refused.
 */
public record UpdateNotificationPreferencesDTO(@NotNull Boolean engagementEmail) {
}
