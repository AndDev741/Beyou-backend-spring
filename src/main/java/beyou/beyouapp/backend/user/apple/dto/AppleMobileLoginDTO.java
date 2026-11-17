package beyou.beyouapp.backend.user.apple.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the iOS app posts after {@code AppleAuthentication.signInAsync}.
 *
 * <p>{@code identityToken} is Apple's signed ID token. {@code nonce} is the RAW value the
 * app generated; the app handed Apple its SHA-256, and the token carries that hash, so a
 * token captured on its way to us cannot be replayed with a different nonce.
 *
 * <p>{@code authorizationCode} is optional: the backend exchanges it for the refresh token
 * that account deletion revokes, and only when the Sign in with Apple key is configured.
 *
 * <p>{@code givenName}/{@code familyName} come from the device, NOT from the token. Apple
 * hands the name to the app once, on the very first authorization, and never puts it in the
 * token. They only name an account being created here, like {@code timezone} and
 * {@code language}, and they are never trusted for anything else.
 */
public record AppleMobileLoginDTO(
        @NotBlank String identityToken,
        @NotBlank @Size(max = 256) String nonce,
        @Size(max = 2048) String authorizationCode,
        @Size(max = 128) String givenName,
        @Size(max = 128) String familyName,
        String timezone,
        String language) {
}
