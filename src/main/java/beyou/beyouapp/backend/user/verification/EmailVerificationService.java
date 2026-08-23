package beyou.beyouapp.backend.user.verification;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Minting and re-sending the token that proves someone owns the address they signed up with.
 *
 * <p>Before this existed, a registration mail that never arrived — spam, bounce, full
 * mailbox — locked the account permanently. Login refuses an unverified account, the
 * email column is unique so registering again is refused too, and the token expired
 * after 24 hours with no way to ask for another. The only repair was an UPDATE by hand.
 *
 * <p><b>Every refusal in {@link #resendVerification(String)} is silent.</b> The endpoint
 * is public and unauthenticated, so the response has to look identical whether the
 * address is unknown, already verified, or inside its cooldown. This is deliberately
 * stricter than {@link beyou.beyouapp.backend.security.passwordreset.PasswordResetService},
 * which returns quietly for an unknown address but throws
 * {@code PASSWORD_RESET_TOO_MANY_REQUESTS} for a known one inside the cooldown — a 400
 * with a named key against a 200, which is an enumeration oracle. Do not "improve" the
 * error reporting here by copying that: the silence is the feature.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final EmailVerificationWrites verificationWrites;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Base64.Encoder base64Encoder = Base64.getUrlEncoder();

    @Value("${email-verification.token-ttl-hours}")
    private long tokenTtlHours;

    @Value("${email-verification.cooldown-seconds}")
    private long cooldownSeconds;

    /**
     * E2E only, and refused in prod by {@code SecurityConfigValidator}. Lets a test read
     * the token it would otherwise have to open an inbox for.
     */
    @Value("${e2e.expose-verification-token:false}")
    private boolean exposeVerificationToken;

    public boolean isTokenExposed() {
        return exposeVerificationToken;
    }

    /**
     * Puts a fresh token on the user and returns it. Does NOT save; the caller owns that,
     * because registration is already saving a brand-new row.
     *
     * <p>Lives here rather than in {@code UserService} so registration and resend cannot
     * drift on the TTL. Whoever adds a third way to issue one of these should call this
     * too instead of writing {@code plusHours(24)} again.
     */
    public String issueToken(User user) {
        String token = generateVerificationToken();
        user.setVerificationToken(token);
        user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(tokenTtlHours));
        return token;
    }

    /**
     * Starts the cooldown. Separate from {@link #issueToken} so the e2e profile, which
     * mints a token and then auto-verifies without mailing anything, does not leave a
     * stamp claiming a mail went out.
     */
    public void markSent(User user) {
        user.setVerificationTokenSentAt(Instant.now());
    }

    /**
     * Mails a new verification link, or quietly does nothing.
     *
     * <p>Replacing the token invalidates the previous one on purpose: two live links in
     * one inbox is a worse experience than one, and the older mail is the one more likely
     * to be buried.
     */
    /**
     * @return the token that was issued, or null when nothing was sent. ALWAYS null
     *         unless {@code e2e.expose-verification-token} is on — the caller must not
     *         let this reach a response outside that profile, and the controller does
     *         not. It is a return value rather than a field so the silence stays the
     *         default: a caller has to ask for it, and only the e2e profile can.
     */
    @Transactional
    public String resendVerification(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return null;
        }

        User user = userOpt.get();

        // Also the Google guard. A row created through Google sign-in is verified by
        // construction (User(GoogleUserDTO) sets the flag), so it leaves here, and
        // mailing a verification link to an account with no password to unlock would
        // be nothing but a confusing message.
        if (user.isEmailVerified()) {
            return null;
        }

        if (isWithinCooldown(user)) {
            return null;
        }

        String token = issueToken(user);
        markSent(user);
        userRepository.save(user);

        scheduleVerificationEmail(user.getId(), user.getEmail(), token, user.getLanguageInUse());
        return exposeVerificationToken ? token : null;
    }

    private boolean isWithinCooldown(User user) {
        Instant lastSent = user.getVerificationTokenSentAt();
        if (lastSent == null) {
            // No mail on record. Every row that predates V23 reads this way, which is
            // the point: those are the accounts the missing resend already stranded,
            // and their first request must not be refused.
            return false;
        }
        return Instant.now().isBefore(lastSent.plus(Duration.ofSeconds(cooldownSeconds)));
    }

    /**
     * Sends after the row is committed, and gives the cooldown back if the send fails.
     *
     * <p>Sending inside the transaction would mail a link whose token can still roll
     * back. Keeping the stamp after a failed send would be worse: the user is looking at
     * a screen that says a mail is on the way, no mail is coming, and the button they
     * would press next is refused for the next minute. Same reasoning as
     * {@code AccountDeletionCodeWrites.discard}, and the same reason the clearing write
     * needs its own transaction — this runs after commit, where data access joins a
     * transaction that will never commit again.
     */
    private void scheduleVerificationEmail(UUID userId, String to, String token, String language) {
        Runnable sendEmail = () -> {
            try {
                emailService.sendVerificationEmail(to, token, language);
            } catch (Exception ex) {
                log.error("Failed to send verification email for user {}", userId, ex);
                verificationWrites.clearSentStamp(userId);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendEmail.run();
                }
            });
        } else {
            sendEmail.run();
        }
    }

    private static String generateVerificationToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return base64Encoder.encodeToString(randomBytes);
    }
}
