package beyou.beyouapp.backend.user.apple;

/**
 * A verified Sign in with Apple identity token, reduced to what sign-in reads.
 *
 * @param subject       Apple's {@code sub}: stable for this person and this team, forever
 * @param email         the address Apple vouches for, or null. Either the person's own, or
 *                      an {@code @privaterelay.appleid.com} relay when they chose to hide it
 * @param emailVerified Apple's {@code email_verified}. Apple sends it as a boolean or as the
 *                      string {@code "true"} depending on the account's age
 * @param privateEmail  Apple's {@code is_private_email}: the address is a relay. It still
 *                      delivers mail, provided the sending domain is registered with Apple
 * @param audience      the {@code aud} the token was issued to (bundle id or Services ID)
 */
public record AppleIdentity(String subject, String email, boolean emailVerified, boolean privateEmail,
                            String audience) {
}
