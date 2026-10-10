package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Mobile Google sign-in payload: the Google-issued ID token obtained on-device
 * via expo-auth-session. Verified server-side against Google's public keys.
 *
 * <p>{@code timezone} is the device's IANA zone, sent alongside because the ID token
 * carries no such claim. Optional, and only applied when the account is created.
 *
 * <p>{@code language} is the language the app is showing, for the same reason and under
 * the same rule. See {@code UserLanguage}.
 */
public record GoogleMobileLoginDTO(@NotBlank String idToken, String timezone, String language) {
}
