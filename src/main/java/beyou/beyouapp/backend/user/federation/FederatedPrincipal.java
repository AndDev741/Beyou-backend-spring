package beyou.beyouapp.backend.user.federation;

/**
 * What every OIDC provider is reduced to before {@link FederatedIdentityService} sees it.
 *
 * <p>One shape for all of them on purpose. The linking rule is the part that is easy to
 * get wrong and expensive to get wrong twice, so it exists once and reads no
 * provider-specific field — a provider added later cannot accidentally get a weaker rule
 * by taking a different path.
 *
 * @param issuer        the verified {@code iss} claim
 * @param subject       the verified {@code sub} claim
 * @param email         the claimed address, which may be null and may be a lie
 * @param emailVerified whether the ISSUER says it proved ownership of {@code email}.
 *                      Never trusted on its own — {@code OidcProviderProperties.trustEmailVerified}
 *                      decides whether this issuer's word counts for anything at all.
 * @param name          display name, may be null
 * @param picture       avatar URL, may be null
 * @param timezone      claimed by the CLIENT, never by the issuer, and only ever applied
 *                      when an account is created — same contract as {@code GoogleUserDTO}
 */
public record FederatedPrincipal(
        String issuer,
        String subject,
        String email,
        boolean emailVerified,
        String name,
        String picture,
        String timezone) {

    public FederatedPrincipal withTimezone(String timezone) {
        return new FederatedPrincipal(issuer, subject, email, emailVerified, name, picture, timezone);
    }
}
