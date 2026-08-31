package beyou.beyouapp.backend.user.federation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * What a client posts after completing the provider's Authorization Code + PKCE dance
 * in the browser (web) or the system browser (mobile).
 *
 * <p>The client sends the ID TOKEN, not the code. The code exchange stays on the client
 * because our registration is a public client with no secret and the {@code code_verifier}
 * never leaves the device that generated it — sending the code here would mean shipping
 * the verifier with it, which defeats PKCE rather than using it.
 *
 * <p>Records used as {@code @RequestBody} take no convenience constructors: Jackson picks
 * a constructor by shape, and a second one gives it a way to pick wrongly.
 */
public record OidcLoginDTO(@NotBlank String idToken, String timezone) {}
