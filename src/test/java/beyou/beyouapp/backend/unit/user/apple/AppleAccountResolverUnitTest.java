package beyou.beyouapp.backend.unit.user.apple;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.apple.AppleAccountResolver;
import beyou.beyouapp.backend.user.apple.AppleAccountResolver.Outcome;
import beyou.beyouapp.backend.user.apple.AppleIdentity;
import beyou.beyouapp.backend.user.dto.GoogleUserDTO;
import beyou.beyouapp.backend.user.federation.FederatedIdentity;
import beyou.beyouapp.backend.user.federation.FederatedIdentityRepository;
import beyou.beyouapp.backend.user.federation.FederatedPrincipal;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppleAccountResolver")
class AppleAccountResolverUnitTest {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String SUB = "001234.abcdef.0987";

    @Mock private FederatedIdentityRepository federatedIdentityRepository;
    @Mock private UserRepository userRepository;

    private AppleAccountResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AppleAccountResolver(federatedIdentityRepository, userRepository);
    }

    private static AppleIdentity identity(String email, boolean verified, boolean relay) {
        return new AppleIdentity(SUB, email, verified, relay, "com.beyou.mobile");
    }

    private void saveReturnsArgument() {
        when(federatedIdentityRepository.save(any(FederatedIdentity.class))).thenAnswer(inv -> {
            FederatedIdentity f = inv.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });
    }

    @Test
    @DisplayName("a known (iss, sub) signs that account in, whatever address the token carries now")
    void knownPairWins() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        FederatedIdentity link = new FederatedIdentity(owner,
                new FederatedPrincipal(ISSUER, SUB, "old@example.test", true, "Old", null),
                LocalDateTime.now().minusDays(30));
        link.setId(UUID.randomUUID());
        when(federatedIdentityRepository.findByIssuerAndSubject(ISSUER, SUB)).thenReturn(Optional.of(link));

        Outcome outcome = resolver.resolve(identity(null, false, false), "Ignored", null, null);

        assertThat(outcome).isInstanceOf(Outcome.SignedIn.class);
        assertThat(((Outcome.SignedIn) outcome).user()).isSameAs(owner);
        // No address on a later sign-in is normal for Apple; the old one is kept for support.
        assertThat(link.getEmailAtLink()).isEqualTo("old@example.test");
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    @DisplayName("an unknown sub with a new verified address creates the account and the link")
    void createsAccountAndLink() {
        when(federatedIdentityRepository.findByIssuerAndSubject(ISSUER, SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.test")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        saveReturnsArgument();

        Outcome outcome = resolver.resolve(identity("new@example.test", true, false), "Ana Silva",
                "Europe/Lisbon", "pt-PT");

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(created.capture());
        assertThat(created.getValue().getName()).isEqualTo("Ana Silva");
        assertThat(created.getValue().getEmail()).isEqualTo("new@example.test");
        assertThat(created.getValue().isEmailVerified()).isTrue();
        assertThat(created.getValue().isGoogleAccount()).isFalse();
        assertThat(created.getValue().getTimezone()).isEqualTo("Europe/Lisbon");

        ArgumentCaptor<FederatedIdentity> link = ArgumentCaptor.forClass(FederatedIdentity.class);
        verify(federatedIdentityRepository).save(link.capture());
        assertThat(link.getValue().getIssuer()).isEqualTo(ISSUER);
        assertThat(link.getValue().getSubject()).isEqualTo(SUB);
        assertThat(outcome).isInstanceOf(Outcome.SignedIn.class);
    }

    @Test
    @DisplayName("a verified address of an existing Google account links Apple to it, like Google's own door")
    void linksExistingVerifiedAccount() {
        User existing = new User(new GoogleUserDTO("same@example.test", "Same", null));
        existing.setId(UUID.randomUUID());
        when(federatedIdentityRepository.findByIssuerAndSubject(ISSUER, SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("same@example.test")).thenReturn(Optional.of(existing));
        when(federatedIdentityRepository.existsByUserIdAndIssuer(existing.getId(), ISSUER)).thenReturn(false);
        saveReturnsArgument();

        Outcome outcome = resolver.resolve(identity("same@example.test", true, false), "Other Name", null, null);

        assertThat(((Outcome.SignedIn) outcome).user()).isSameAs(existing);
        // Linking never renames or otherwise rewrites the account it joins.
        assertThat(existing.getName()).isEqualTo("Same");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("a password account that never verified its address is refused, not entered")
    void refusesUnverifiedPasswordAccount() {
        User squatted = new User();
        squatted.setId(UUID.randomUUID());
        squatted.setEmail("victim@example.test");
        squatted.setEmailVerified(false);
        when(federatedIdentityRepository.findByIssuerAndSubject(ISSUER, SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("victim@example.test")).thenReturn(Optional.of(squatted));

        Outcome outcome = resolver.resolve(identity("victim@example.test", true, false), "Victim", null, null);

        assertThat(outcome).isInstanceOf(Outcome.EmailNotVerified.class);
        verify(federatedIdentityRepository, never()).save(any());
    }

    @Test
    @DisplayName("a second Apple ID for an account that already has one is refused")
    void refusesSecondAppleId() {
        User existing = new User(new GoogleUserDTO("same@example.test", "Same", null));
        existing.setId(UUID.randomUUID());
        when(federatedIdentityRepository.findByIssuerAndSubject(ISSUER, SUB)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("same@example.test")).thenReturn(Optional.of(existing));
        when(federatedIdentityRepository.existsByUserIdAndIssuer(existing.getId(), ISSUER)).thenReturn(true);

        assertThatThrownBy(() -> resolver.resolve(identity("same@example.test", true, false), "x", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.FEDERATED_IDENTITY_ISSUER_ALREADY_LINKED);
        verify(federatedIdentityRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unknown sub with no verified address cannot create anything")
    void refusesWithoutVerifiedAddress() {
        when(federatedIdentityRepository.findByIssuerAndSubject(ISSUER, SUB)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(identity("unproven@example.test", false, false), "x", null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> resolver.resolve(identity(null, false, false), "x", null, null))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findByEmail(any());
    }
}
