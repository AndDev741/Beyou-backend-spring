package beyou.beyouapp.backend.user.verification;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * The one write to a verification stamp that must not share its caller's transaction.
 *
 * <p>It is called from an {@code afterCommit} callback, where data access joins a
 * transaction that has already committed and will not commit again, so without
 * {@code REQUIRES_NEW} the update never lands.
 *
 * <p>A separate bean rather than a method on the service, because Spring's transaction
 * advice lives in the proxy: a self-call would run in the caller's transaction and change
 * nothing. Same shape and same reason as {@code AccountDeletionCodeWrites}.
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationWrites {

    private final UserRepository userRepository;

    /** Gives the cooldown back to a user whose mail never went out. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearSentStamp(UUID userId) {
        userRepository.clearVerificationTokenSentAt(userId);
    }
}
