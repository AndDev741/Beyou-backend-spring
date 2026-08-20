package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Mobile Google sign-in payload: the Google-issued ID token obtained on-device
 * via expo-auth-session. Verified server-side against Google's public keys.
 *
 * <p>{@code timezone} is the device's IANA zone, sent alongside because the ID token
 * carries no such claim. Optional, and only applied when the account is created.
 */
public record GoogleMobileLoginDTO(@NotBlank String idToken, String timezone) {
}
