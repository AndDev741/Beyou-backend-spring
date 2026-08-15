package beyou.beyouapp.backend.user.deletion;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The two writes to a deletion code that must not share their caller's transaction.
 *
 * Counting a wrong guess is discarded otherwise: the refusal is a throw, and the
 * throw is what rolls the surrounding transaction back. That left {@code attempts}
 * at zero forever and made the five-try cap dead code, leaving nothing but the
 * generic write bucket between a six-digit space and a walk through it.
 *
 * Dropping an unsent code has the mirror problem: it is called from an
 * {@code afterCommit} callback, where data access joins a transaction that has
 * already committed and will not commit again, so the delete never landed.
 *
 * A separate bean rather than methods on the service, because Spring's transaction
 * advice lives in the proxy: a self-call would run in the caller's transaction and
 * change nothing.
 */
@Component
@RequiredArgsConstructor
public class AccountDeletionCodeWrites {

    private final AccountDeletionCodeRepository codeRepository;

    /** Counts one wrong guess, in SQL, so two racing guesses still count as two. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID codeId) {
        codeRepository.recordFailedAttempt(codeId);
    }

    /** Drops a code whose email never went out, so its cooldown is not held against someone who received nothing. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discard(UUID codeId) {
        codeRepository.deleteById(codeId);
    }
}
