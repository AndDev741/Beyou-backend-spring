package beyou.beyouapp.backend.user.apple;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.federation.FederatedIdentityRepository;
import beyou.beyouapp.backend.user.federation.ProviderToken;

@DisplayName("AppleTokenRevocation")
class AppleTokenRevocationTest {

    private static final String APPLE = "https://appleid.apple.com";

    private FederatedIdentityRepository repository;
    private AppleTokenClient client;
    private AppleTokenRevocation revocation;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(FederatedIdentityRepository.class);
        client = mock(AppleTokenClient.class);
        revocation = new AppleTokenRevocation(repository, client);
        user = new User();
        user.setId(UUID.randomUUID());
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("the Apple refresh token is revoked only once the deletion commits")
    void revokesAfterCommit() {
        when(repository.findProviderTokens(user.getId(), APPLE))
                .thenReturn(List.of(new ProviderToken("apple-refresh", "com.beyou.mobile")));
        when(client.isConfigured()).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        revocation.revokeAfterCommit(user);

        // Nothing yet: a rollback after this point must leave the Apple link alone.
        verify(client, never()).revokeRefreshToken(anyString(), anyString());

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(client).revokeRefreshToken("apple-refresh", "com.beyou.mobile");
    }

    @Test
    @DisplayName("an account with no Apple link touches nothing")
    void noAppleLink() {
        when(repository.findProviderTokens(user.getId(), APPLE)).thenReturn(List.of());
        when(repository.existsByUserIdAndIssuer(user.getId(), APPLE)).thenReturn(false);

        revocation.revokeAfterCommit(user);

        verify(client, never()).revokeRefreshToken(anyString(), anyString());
    }

    @Test
    @DisplayName("an Apple link without a kept token, or without the key, is logged and skipped")
    void skipsWhenThereIsNothingToRevokeWith() {
        when(repository.findProviderTokens(user.getId(), APPLE)).thenReturn(List.of());
        when(repository.existsByUserIdAndIssuer(user.getId(), APPLE)).thenReturn(true);
        revocation.revokeAfterCommit(user);

        when(repository.findProviderTokens(user.getId(), APPLE))
                .thenReturn(List.of(new ProviderToken("apple-refresh", "com.beyou.mobile")));
        when(client.isConfigured()).thenReturn(false);
        revocation.revokeAfterCommit(user);

        verify(client, never()).revokeRefreshToken(anyString(), anyString());
    }
}
