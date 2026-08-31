package beyou.beyouapp.backend.user.federation;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The one place that decides which beyou account a verified external identity opens.
 *
 * <p><b>Read this before adding a provider.</b> The rule below is not defensive
 * programming; it is the difference between "another way in for you" and "another way in
 * to you, for whoever runs that server". Every provider funnels through
 * {@link #resolve} — there is no second path, on purpose, because a seventh provider
 * added in two years cannot forget a rule it has no way to bypass.
 *
 * <p>The rule, in order:
 *
 * <ol>
 *   <li><b>Known pair {@code (iss, sub)}</b> — log in, and touch {@code lastLoginAt}.
 *       This is the only lookup that authenticates anybody.
 *   <li><b>Address not trusted</b> — refuse with {@code LinkRequired}. Covers both a
 *       provider we do not trust on this point and a token that admitted
 *       {@code email_verified: false}. Nothing is created and nothing is matched.
 *   <li><b>Address trusted and already in use</b> — refuse with {@code LinkRequired}.
 *       The account exists; joining a new door to it is a decision taken from inside.
 *   <li><b>Address trusted and unknown</b> — create the account and the link together.
 * </ol>
 *
 * <p>Note what is deliberately absent: no branch anywhere resolves a user by the address
 * the token claimed. {@code email_at_link} is written and never read back into a lookup.
 */
@Service
@RequiredArgsConstructor
public class FederatedIdentityService {

    private final FederatedIdentityRepository federatedIdentityRepository;
    private final UserRepository userRepository;

    /**
     * Decides what a verified identity may do, and performs it when it is a login.
     *
     * @param principal a principal whose signature, issuer, audience and expiry the
     *                  caller has ALREADY verified. This method verifies nothing
     *                  cryptographic and must never be handed unverified claims.
     * @param provider  the configuration for {@code principal.issuer()}
     */
    @Transactional
    public FederationOutcome resolve(FederatedPrincipal principal, OidcProviderProperties.Provider provider) {
        LocalDateTime now = LocalDateTime.now();

        Optional<FederatedIdentity> existing =
                federatedIdentityRepository.findByIssuerAndSubject(principal.issuer(), principal.subject());

        if (existing.isPresent()) {
            FederatedIdentity identity = existing.get();
            identity.setLastLoginAt(now);
            // The claimed address is refreshed for support, not consulted for anything.
            identity.setEmailAtLink(principal.email());
            federatedIdentityRepository.save(identity);
            return new FederationOutcome.LoggedIn(identity.getUser());
        }

        boolean addressTrusted = provider.isTrustEmailVerified()
                && principal.emailVerified()
                && principal.email() != null
                && !principal.email().isBlank();

        if (!addressTrusted) {
            return new FederationOutcome.LinkRequired(
                    FederationOutcome.LinkRequired.Reason.EMAIL_NOT_TRUSTED, principal.email());
        }

        if (userRepository.findByEmail(principal.email()).isPresent()) {
            return new FederationOutcome.LinkRequired(
                    FederationOutcome.LinkRequired.Reason.ACCOUNT_EXISTS, principal.email());
        }

        User created = userRepository.save(User.fromFederatedPrincipal(principal));
        federatedIdentityRepository.save(new FederatedIdentity(created, principal, now));
        return new FederationOutcome.LoggedIn(created);
    }

    /**
     * Attaches an external identity to an account that has already authenticated.
     *
     * <p>This is the door {@code LinkRequired} sends people to, and the only way an
     * untrusted provider ever reaches an existing account. The session proves who is
     * asking; the token proves which external identity is being attached.
     *
     * @throws BusinessException if the identity already belongs to a different account,
     *                           or this account already has a link to the same issuer
     */
    @Transactional
    public FederatedIdentity link(User user, FederatedPrincipal principal) {
        LocalDateTime now = LocalDateTime.now();

        Optional<FederatedIdentity> existing =
                federatedIdentityRepository.findByIssuerAndSubject(principal.issuer(), principal.subject());

        if (existing.isPresent()) {
            FederatedIdentity identity = existing.get();
            if (identity.getUser().getId().equals(user.getId())) {
                // Linking twice from two tabs is not an error; it is the same request.
                identity.setLastLoginAt(now);
                return federatedIdentityRepository.save(identity);
            }
            throw new BusinessException(ErrorKey.FEDERATED_IDENTITY_ALREADY_LINKED,
                    "This account at the provider is already linked to another beyou account");
        }

        // One identity per issuer per account. Without this, a second link silently
        // shadows the first and unlinking becomes ambiguous.
        if (federatedIdentityRepository.existsByUserIdAndIssuer(user.getId(), principal.issuer())) {
            throw new BusinessException(ErrorKey.FEDERATED_IDENTITY_ISSUER_ALREADY_LINKED,
                    "This account is already linked to that provider");
        }

        return federatedIdentityRepository.save(new FederatedIdentity(user, principal, now));
    }

    /**
     * Records the Google identity of an account that signed in through the email path.
     *
     * <p>Google predates this table and its rows were matched by address, so there is
     * nothing to migrate from — we never stored its subject. Rather than a backfill that
     * cannot be written, the row appears on the account's next Google sign-in, and from
     * then on that account resolves by {@code (iss, sub)} like everything else.
     *
     * <p>Silent when the subject is missing: an older deployment whose userinfo response
     * carries no id still logs its users in through the address path, which stays correct
     * for Google specifically because Google verifies the addresses it asserts.
     */
    @Transactional
    public void recordSeenIdentity(User user, FederatedPrincipal principal) {
        if (principal.subject() == null || principal.subject().isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        federatedIdentityRepository.findByIssuerAndSubject(principal.issuer(), principal.subject())
                .ifPresentOrElse(
                        identity -> {
                            identity.setLastLoginAt(now);
                            federatedIdentityRepository.save(identity);
                        },
                        () -> {
                            if (!federatedIdentityRepository.existsByUserIdAndIssuer(user.getId(), principal.issuer())) {
                                federatedIdentityRepository.save(new FederatedIdentity(user, principal, now));
                            }
                        });
    }
}
