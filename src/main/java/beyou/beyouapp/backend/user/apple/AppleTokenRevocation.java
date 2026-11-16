package beyou.beyouapp.backend.user.apple;

import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.federation.FederatedIdentityRepository;
import beyou.beyouapp.backend.user.federation.ProviderToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * Revokes an account's Sign in with Apple link when the account is deleted, as App Store
 * guideline 5.1.1(v) asks of any app that offers both.
 *
 * <p>Called by {@code AccountDeletionService.confirm} just before the delete. The token is
 * READ now, because the row cascades away with the account, and Apple is CALLED after the
 * commit, for the same reason {@code UserService.deleteUser} purges attachment files after
 * it: a revocation done inside the transaction would survive a rollback that kept the
 * account. If the delete rolls back, nothing is revoked.
 *
 * <p>No-op, with a log line that says why, when the account has no Apple link, when the
 * link has no refresh token (the key was not configured when the person signed in), or
 * when the key is not configured now. The deletion itself never waits on Apple.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppleTokenRevocation {

    private final FederatedIdentityRepository federatedIdentityRepository;
    private final AppleTokenClient tokenClient;

    public void revokeAfterCommit(User user) {
        UUID userId = user.getId();
        // Plain values, not entities: see FederatedIdentityRepository.findProviderTokens.
        List<ProviderToken> pending =
                federatedIdentityRepository.findProviderTokens(userId, AppleAccountResolver.APPLE_ISSUER);
        if (pending.isEmpty()) {
            if (federatedIdentityRepository.existsByUserIdAndIssuer(userId, AppleAccountResolver.APPLE_ISSUER)) {
                log.warn("Account {} is linked to Sign in with Apple but no Apple refresh token was kept "
                        + "(the Sign in with Apple key was not configured when it signed in): the Apple link "
                        + "is not revoked and stays under the person's Apple ID settings", userId);
            }
            return;
        }
        if (!tokenClient.isConfigured()) {
            log.warn("Sign in with Apple key not configured (APPLE_TEAM_ID / APPLE_KEY_ID / APPLE_PRIVATE_KEY): "
                    + "the Apple link of deleted account {} is not revoked", userId);
            return;
        }

        Runnable revoke = () -> pending.forEach(p -> {
            boolean revoked = tokenClient.revokeRefreshToken(p.refreshToken(), p.clientId());
            if (revoked) {
                log.info("Revoked the Sign in with Apple token of deleted account {}", userId);
            } else {
                log.error("Apple refused or did not answer the revocation for deleted account {}; "
                        + "the account is gone, the Apple link stays until the person removes it", userId);
            }
        });

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    revoke.run();
                }
            });
        } else {
            revoke.run();
        }
    }
}
