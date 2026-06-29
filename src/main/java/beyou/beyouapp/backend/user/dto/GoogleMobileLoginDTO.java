package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Mobile Google sign-in payload: the Google-issued ID token obtained on-device
 * via expo-auth-session. Verified server-side against Google's public keys.
 */
public record GoogleMobileLoginDTO(@NotBlank String idToken) {
}
