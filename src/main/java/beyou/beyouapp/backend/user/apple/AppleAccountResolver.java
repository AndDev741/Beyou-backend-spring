package beyou.beyouapp.backend.user.apple;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.federation.FederatedIdentity;
import beyou.beyouapp.backend.user.federation.FederatedIdentityRepository;
import beyou.beyouapp.backend.user.federation.FederatedPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides which beyou account a verified Apple identity opens, in one transaction.
 *
 * <p>The rule mirrors Google's mobile sign-in, because Apple, like Google, proves the
 * addresses it asserts (an Apple ID cannot carry an address its owner never confirmed, and
 * a private relay address is minted by Apple itself):
 *
 * <ol>
 *   <li><b>Known pair {@code (https://appleid.apple.com, sub)}</b>: that account. The pair
 *       is the identity; the address is only read when there is no pair yet.</li>
 *   <li><b>No pair, verified address of an existing account</b>: link Apple to it and sign
 *       in, so someone who used Google or a password on Android finds their data on the
 *       iPhone. Refused, exactly like the Google doors, when that account is a password
 *       account that never verified its address: see
 *       {@code UserServiceGoogleOAuth.isUnverifiedLocalAccount} for the squatting attack
 *       that closes.</li>
 *   <li><b>No pair, unknown address</b>: create the account and the link together.</li>
 * </ol>
 *
 * <p>A relay address only matches an account that was created through Apple in the first
 * place, so someone who hides their email gets a new account rather than their old one.
 * That is Apple's design, not something this class can see through.
 *
 * <p>Unlike {@code FederatedIdentityService.resolve} (third-party OIDC providers), the
 * address IS allowed to find an existing account here. That class's rule is right for an
 * issuer whose operator we do not vouch for; Apple and Google are the two we do, and the
 * mobile app has no "link from settings" screen for the refusal to send people to.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppleAccountResolver {

    /** Apple's {@code iss}, as stored in {@code federated_identities.issuer}. */
    public static final String APPLE_ISSUER = "https://appleid.apple.com";

    private final FederatedIdentityRepository federatedIdentityRepository;
    private final UserRepository userRepository;

    public sealed interface Outcome {
        /** Sign this account in. {@code identityId} is the Apple link row, new or old. */
        record SignedIn(User user, UUID identityId, boolean hasRefreshToken) implements Outcome {}

        /** The address belongs to a password account that never verified it. */
        record EmailNotVerified() implements Outcome {}
    }

    /**
     * @param identity a VERIFIED Apple identity. Nothing cryptographic is checked here.
     * @param name     the display name for an account created here; ignored otherwise
     */
    @Transactional
    public Outcome resolve(AppleIdentity identity, String name, String claimedTimezone, String claimedLanguage) {
        LocalDateTime now = LocalDateTime.now();

        Optional<FederatedIdentity> known =
                federatedIdentityRepository.findByIssuerAndSubject(APPLE_ISSUER, identity.subject());
        if (known.isPresent()) {
            FederatedIdentity link = known.get();
            link.setLastLoginAt(now);
            if (identity.email() != null) {
                // Refreshed for support, never consulted.
                link.setEmailAtLink(identity.email());
            }
            federatedIdentityRepository.save(link);
            // Unproxied here, while the session is open: the caller issues tokens after commit.
            User user = Hibernate.unproxy(link.getUser(), User.class);
            return new Outcome.SignedIn(user, link.getId(), link.getProviderRefreshToken() != null);
        }

        if (identity.email() == null || !identity.emailVerified()) {
            // Apple always shares an address (the person's own, or a relay) when the app asks
            // for the email scope, so this is a client that did not ask. Without an address
            // there is nothing to create an account with.
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "Apple did not share a verified email");
        }

        FederatedPrincipal principal = new FederatedPrincipal(
                APPLE_ISSUER, identity.subject(), identity.email(), true, name, null)
                .withClientClaims(claimedTimezone, claimedLanguage);

        Optional<User> byEmail = userRepository.findByEmail(identity.email());
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            if (!user.isGoogleAccount() && !user.isEmailVerified()) {
                return new Outcome.EmailNotVerified();
            }
            if (federatedIdentityRepository.existsByUserIdAndIssuer(user.getId(), APPLE_ISSUER)) {
                // A second Apple ID claiming the same verified address. One link per issuer
                // per account, as everywhere else; the existing one is not replaced silently.
                log.warn("Refusing a second Apple identity for account {}", user.getId());
                throw new BusinessException(ErrorKey.FEDERATED_IDENTITY_ISSUER_ALREADY_LINKED,
                        "This account is already linked to another Apple ID");
            }
            FederatedIdentity link = federatedIdentityRepository.save(new FederatedIdentity(user, principal, now));
            log.info("Linked Sign in with Apple to existing account {}", user.getId());
            return new Outcome.SignedIn(user, link.getId(), false);
        }

        User created = userRepository.save(User.fromFederatedPrincipal(principal));
        FederatedIdentity link = federatedIdentityRepository.save(new FederatedIdentity(created, principal, now));
        log.info("Created account {} through Sign in with Apple (relay address: {})",
                created.getId(), identity.privateEmail());
        return new Outcome.SignedIn(created, link.getId(), false);
    }

    /** Stores the refresh token account deletion will revoke. Own transaction, after the HTTP call. */
    @Transactional
    public void storeRefreshToken(UUID identityId, String refreshToken, String clientId) {
        federatedIdentityRepository.findById(identityId).ifPresent(link -> {
            link.setProviderRefreshToken(refreshToken);
            link.setProviderClientId(clientId);
            federatedIdentityRepository.save(link);
        });
    }
}
