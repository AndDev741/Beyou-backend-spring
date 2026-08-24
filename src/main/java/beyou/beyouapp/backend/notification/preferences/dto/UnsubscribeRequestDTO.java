package beyou.beyouapp.backend.notification.preferences.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The token from an unsubscribe link, posted by the page the link opens.
 *
 * <p>In a body rather than a query parameter on purpose. Query strings end up in browser
 * history, in {@code Referer} headers on the next navigation, and in the analytics
 * scrubber's list of things it has to strip (see `stripUrlQuery` on the web side, which
 * exists because an OAuth code arrived that way). A capability should not travel where a
 * URL travels.
 *
 * <p>The size bound matches the column: 32 random bytes as unpadded url-safe base64 is
 * 43 characters, and nothing longer than the column can possibly match a row.
 */
public record UnsubscribeRequestDTO(@NotBlank @Size(max = 64) String token) {
}
