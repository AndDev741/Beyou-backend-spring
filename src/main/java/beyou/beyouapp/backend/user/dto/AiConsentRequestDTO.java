package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /user/ai-consent}: the person's yes or no. There is no way back to "not asked".
 *
 * @param onlyIfUnasked true when a phone hands over an answer it kept while the server could
 *                      not store one: it lands only on an account nobody has answered for
 *                      yet, so it never overwrites an answer given since on another device.
 *                      The response carries whatever the account holds afterwards.
 */
public record AiConsentRequestDTO(@NotNull Boolean granted, Boolean onlyIfUnasked) {
}
