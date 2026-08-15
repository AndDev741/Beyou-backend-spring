package beyou.beyouapp.backend.user.deletion;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The two halves of deleting an account: asking for a code, and spending it.
 *
 * A valid session is not enough to authorize this. It is the one irreversible
 * action in the app, and an unlocked phone on a table carries a valid session, so
 * the email account has to agree as well. BeYou mails six digits and nothing is
 * destroyed until they come back.
 *
 * The code is stored as a BCrypt hash, expires, is single-use, and dies after
 * {@value #MAX_ATTEMPTS} wrong tries — six digits is a million guesses, which is
 * few enough to be worth walking through if a code could be tried forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountDeletionService {

    /** Wrong tries a single code survives before it has to be requested again. */
    static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AccountDeletionCodeRepository codeRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final UserService userService;

    @Value("${account-deletion.code-ttl-minutes:15}")
    private long codeTtlMinutes;

    @Value("${account-deletion.cooldown-seconds:60}")
    private long cooldownSeconds;

    /**
     * Hands the code back to the caller instead of only mailing it. ONLY the e2e
     * profile turns this on (application-e2e.yml), so a Playwright run can finish
     * the flow without an inbox. Always false in dev and prod, exactly like
     * {@code e2e.auto-verify-email}.
     */
    @Value("${e2e.expose-deletion-code:false}")
    private boolean exposeCode;

    /**
     * Mails a fresh code and invalidates any earlier one, so the newest mail is
     * always the only one that works.
     */
    @Transactional
    public String requestCode(User user) {
        enforceCooldown(user);
        Timestamp now = Timestamp.from(Instant.now());
        codeRepository.invalidateActiveCodes(user.getId(), now, now);

        String rawCode = generateCode();
        AccountDeletionCode code = new AccountDeletionCode();
        code.setUser(user);
        code.setCodeHash(passwordEncoder.encode(rawCode));
        code.setCreatedAt(now);
        code.setExpiresAt(Timestamp.from(Instant.now().plus(Duration.ofMinutes(codeTtlMinutes))));
        codeRepository.save(code);

        sendAfterCommit(user, code.getId(), rawCode);
        return exposeCode ? rawCode : null;
    }

    /**
     * Spends the code and deletes the account.
     *
     * Spending means deleting the row, not marking it used. A used row would stay
     * MANAGED in this session while pointing at a user that is about to be deleted,
     * and Hibernate checks that reference before the database's ON DELETE CASCADE
     * ever gets a say: the route failed on TransientPropertyValueException the first
     * time it ran for real. Deleting the entity takes it out of the session too, and
     * a code that no longer exists is as single-use as one marked spent.
     *
     * Both halves are in this transaction, so a failed delete rolls the code back
     * with it and the user can try again.
     */
    @Transactional
    public ResponseEntity<Map<String, String>> confirm(User user, String rawCode) {
        AccountDeletionCode code = codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new BusinessException(ErrorKey.DELETION_CODE_INVALID,
                        "No deletion code was requested for this account"));

        if (code.getUsedAt() != null) {
            throw new BusinessException(ErrorKey.DELETION_CODE_INVALID, "Deletion code already used");
        }
        if (code.getExpiresAt().before(Timestamp.from(Instant.now()))) {
            throw new BusinessException(ErrorKey.DELETION_CODE_EXPIRED, "Deletion code expired");
        }
        if (code.getAttempts() >= MAX_ATTEMPTS) {
            throw new BusinessException(ErrorKey.DELETION_CODE_TOO_MANY_ATTEMPTS,
                    "Too many wrong codes, request a new one");
        }
        if (!passwordEncoder.matches(rawCode == null ? "" : rawCode.trim(), code.getCodeHash())) {
            // Counted before the throw, and saved even though this transaction is not
            // rolling back — a wrong code is a normal outcome here, not a failure.
            code.setAttempts(code.getAttempts() + 1);
            codeRepository.save(code);
            throw new BusinessException(ErrorKey.DELETION_CODE_INVALID, "Deletion code invalid");
        }

        codeRepository.delete(code);

        log.info("Deleting account {} after a confirmed deletion code", user.getId());
        return userService.deleteUser(user);
    }

    private void enforceCooldown(User user) {
        codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId()).ifPresent(latest -> {
            Instant nextAllowed = latest.getCreatedAt().toInstant().plusSeconds(cooldownSeconds);
            if (Instant.now().isBefore(nextAllowed)) {
                throw new BusinessException(ErrorKey.DELETION_CODE_TOO_MANY_REQUESTS,
                        "A deletion code was just sent, wait before asking for another");
            }
        });
    }

    /**
     * Mail goes out only once the code row is durable. The reverse order would let a
     * rollback leave a code in someone's inbox that the app has never heard of, and
     * a failed send drops the row: nobody received that code, so keeping it alive
     * would only hold the cooldown against a user who has nothing to type.
     *
     * Unless the code was handed back in the response. Under the e2e profile there
     * is no SMTP at all, so every send fails and the cleanup was deleting the row
     * for a code the caller already had in its hand — the flow could not be
     * completed there at all. When the caller has it, a failed mail costs nothing.
     */
    private void sendAfterCommit(User user, UUID codeId, String rawCode) {
        Duration ttl = Duration.ofMinutes(codeTtlMinutes);
        Runnable send = () -> {
            try {
                emailService.sendAccountDeletionCodeEmail(user.getEmail(), rawCode, ttl, user.getLanguageInUse());
            } catch (Exception e) {
                log.error("Failed to send the account deletion code for user {}", user.getId(), e);
                if (!exposeCode) {
                    cleanupUnsentCode(codeId, user.getId());
                }
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void cleanupUnsentCode(UUID codeId, UUID userId) {
        try {
            codeRepository.deleteById(codeId);
        } catch (Exception e) {
            log.error("Failed to clean up the unsent deletion code {} for user {}", codeId, userId, e);
        }
    }

    /** Six digits, zero-padded, from a source worth trusting. */
    private String generateCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
