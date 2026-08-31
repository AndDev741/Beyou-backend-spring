package beyou.beyouapp.backend.unit.user.federation;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.federation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The linking rule, which is the whole reason {@code federated_identities} exists.
 *
 * <p>Every test here is about the same question asked from a different angle: can a second
 * identity provider reach a beyou account that is not its own? Before this table the answer
 * was yes for any provider, because both Google entry points found the account by EMAIL —
 * safe only while the single issuer actually proved the addresses it asserted.
 *
 * <p>The case that matters most is {@link #verifiedAddressOfAnExistingAccountDoesNotEnterIt()}.
 * It is deliberately set up as the strongest possible claim a provider can make — the
 * address is asserted, the issuer says it verified it, and we have configured that issuer
 * as trusted — and it still must not open somebody else's account.
 */
class FederatedIdentityServiceUnitTest {

    private static final String ISSUER = "https://backend.omelhorsite.pt";
    private static final String SUBJECT = "hzQwNXgk4klI";
    private static final String VICTIM_EMAIL = "victim@gmail.com";

    private FederatedIdentityRepository identities;
    private UserRepository users;
    private FederatedIdentityService service;
    private OidcProviderProperties.Provider trusting;
    private OidcProviderProperties.Provider distrusting;

    @BeforeEach
    void setUp() {
        identities = mock(FederatedIdentityRepository.class);
        users = mock(UserRepository.class);
        service = new FederatedIdentityService(identities, users);

        trusting = new OidcProviderProperties.Provider();
        trusting.setIssuer(ISSUER);
        trusting.setClientId("client");
        trusting.setTrustEmailVerified(true);

        distrusting = new OidcProviderProperties.Provider();
        distrusting.setIssuer(ISSUER);
        distrusting.setClientId("client");
        distrusting.setTrustEmailVerified(false);

        when(identities.save(any(FederatedIdentity.class)))
                .thenAnswer(i -> i.getArgument(0));
        when(users.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID());
            return u;
        });
    }

    private FederatedPrincipal principal(String email, boolean verified) {
        return new FederatedPrincipal(ISSUER, SUBJECT, email, verified, "Someone", null, null);
    }

    private User existingAccount(String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setEmailVerified(true);
        return user;
    }

    /**
     * The takeover this table exists to prevent.
     *
     * <p>Reverting {@code resolve} to the old find-or-create-by-email shape turns this
     * green in the worst possible way: it returns {@code LoggedIn} holding the victim's
     * account. That is the whole bug in one assertion.
     */
    @Test
    @DisplayName("a verified address belonging to an existing account does not open it")
    void verifiedAddressOfAnExistingAccountDoesNotEnterIt() {
        User victim = existingAccount(VICTIM_EMAIL);
        when(identities.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.empty());
        when(users.findByEmail(VICTIM_EMAIL)).thenReturn(Optional.of(victim));

        FederationOutcome outcome = service.resolve(principal(VICTIM_EMAIL, true), trusting);

        assertInstanceOf(FederationOutcome.LinkRequired.class, outcome,
                "an unlinked identity must never enter an account that already exists");
        assertEquals(FederationOutcome.LinkRequired.Reason.ACCOUNT_EXISTS,
                ((FederationOutcome.LinkRequired) outcome).reason());
        verify(users, never()).save(any(User.class));
        verify(identities, never()).save(any(FederatedIdentity.class));
    }

    @Test
    @DisplayName("an unverified address creates nothing and matches nothing")
    void unverifiedAddressIsRefused() {
        when(identities.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.empty());

        FederationOutcome outcome = service.resolve(principal(VICTIM_EMAIL, false), trusting);

        assertEquals(FederationOutcome.LinkRequired.Reason.EMAIL_NOT_TRUSTED,
                ((FederationOutcome.LinkRequired) outcome).reason());
        verify(users, never()).save(any(User.class));
        // Never even asked: the address is not evidence of anything, so it is not a query.
        verify(users, never()).findByEmail(any());
    }

    @Test
    @DisplayName("a provider we do not trust on addresses cannot create an account")
    void distrustedProviderCannotCreate() {
        when(identities.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.empty());

        FederationOutcome outcome = service.resolve(principal("new@example.com", true), distrusting);

        assertEquals(FederationOutcome.LinkRequired.Reason.EMAIL_NOT_TRUSTED,
                ((FederationOutcome.LinkRequired) outcome).reason());
        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a known (issuer, subject) logs in regardless of what address it now claims")
    void knownPairIgnoresTheClaimedAddress() {
        User owner = existingAccount("owner@example.com");
        FederatedIdentity link = new FederatedIdentity(owner,
                principal("owner@example.com", true), java.time.LocalDateTime.now());
        when(identities.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(link));

        // The provider now claims somebody else's address for the same subject. The pair
        // is what identifies the account, so this changes nothing about who logs in.
        FederationOutcome outcome = service.resolve(principal(VICTIM_EMAIL, true), trusting);

        assertInstanceOf(FederationOutcome.LoggedIn.class, outcome);
        assertSame(owner, ((FederationOutcome.LoggedIn) outcome).user());
        verify(users, never()).findByEmail(any());
    }

    @Test
    @DisplayName("a trusted, unknown address creates the account and its link together")
    void trustedUnknownAddressCreates() {
        when(identities.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.empty());
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());

        FederationOutcome outcome = service.resolve(principal("new@example.com", true), trusting);

        assertInstanceOf(FederationOutcome.LoggedIn.class, outcome);
        User created = ((FederationOutcome.LoggedIn) outcome).user();
        assertEquals("new@example.com", created.getEmail());
        assertTrue(created.isEmailVerified());
        assertFalse(created.isGoogleAccount(), "the flag means Google specifically");
        verify(identities).save(any(FederatedIdentity.class));
    }

    @Test
    @DisplayName("linking an identity that belongs to another account is refused")
    void linkRefusesAnIdentityOwnedByAnotherAccount() {
        User stranger = existingAccount("stranger@example.com");
        User me = existingAccount("me@example.com");
        when(identities.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(
                Optional.of(new FederatedIdentity(stranger, principal("stranger@example.com", true),
                        java.time.LocalDateTime.now())));

        assertThrows(BusinessException.class, () -> service.link(me, principal("me@example.com", true)));
        verify(identities, never()).save(any(FederatedIdentity.class));
    }

    @Test
    @DisplayName("linking twice from two tabs is the same request, not an error")
    void linkIsIdempotentForTheSameAccount() {
        User me = existingAccount("me@example.com");
        FederatedIdentity mine = new FederatedIdentity(me, principal("me@example.com", true),
                java.time.LocalDateTime.now());
        when(identities.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(mine));

        assertDoesNotThrow(() -> service.link(me, principal("me@example.com", true)));
    }

    @Test
    @DisplayName("Google backfill writes nothing when the subject is missing")
    void backfillIsSilentWithoutASubject() {
        User user = existingAccount("someone@gmail.com");

        service.recordSeenIdentity(user, new FederatedPrincipal(
                "https://accounts.google.com", null, "someone@gmail.com", true, null, null, null));

        verify(identities, never()).save(any(FederatedIdentity.class));
    }
}
