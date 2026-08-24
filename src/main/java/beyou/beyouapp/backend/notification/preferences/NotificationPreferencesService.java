package beyou.beyouapp.backend.notification.preferences;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The one place that decides whether an account may be sent engagement mail.
 *
 * <p>Two callers, with opposite postures. The settings screen asks about the account it
 * is authenticated as. The unsubscribe link asks about whoever holds a token, with no
 * session at all — so that path never takes a user id from the caller, only the token,
 * and the token is the entire proof of ownership.
 *
 * <p><b>A missing row is an opted-in account, not an opted-out one.</b> {@code V24}
 * writes no rows and backfills nothing, so most accounts have none until they open
 * settings or are mailed for the first time. Every read here funnels through
 * {@link #getOrCreate(User)} so no caller can accidentally read absence as refusal —
 * which would silently disable the whole feature for every existing account.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationPreferencesService {

    private final NotificationPreferencesRepository repository;

    /** 32 bytes, url-safe and unpadded: 43 characters, inside the column's 64. */
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * The account's preferences, creating the row (and minting its token) the first time
     * anyone asks.
     *
     * <p>Not read-only, and that is the point: the token has to exist before a mail can
     * link to it, and the first mail is often the first time this row is needed at all.
     *
     * <p>The insert races itself. Two requests for an account with no row — a settings
     * screen open in two tabs, or the nightly pass overlapping a login — both see
     * nothing and both insert. The primary key on {@code user_id} makes the loser fail
     * rather than write a second row, and the loser then re-reads the winner's row. The
     * alternative, locking the user first, would serialise every send behind a row lock
     * to avoid something the database already refuses.
     */
    @Transactional
    public NotificationPreferences getOrCreate(User user) {
        return repository.findById(user.getId())
                .orElseGet(() -> insertDefaults(user));
    }

    private NotificationPreferences insertDefaults(User user) {
        NotificationPreferences preferences = new NotificationPreferences();
        preferences.setUser(user);
        preferences.setEngagementEmail(true);
        preferences.setUnsubscribeToken(generateToken());

        try {
            return repository.saveAndFlush(preferences);
        } catch (DataIntegrityViolationException raced) {
            // Somebody else inserted between our read and our write. Their row is as
            // good as the one we were about to write, and re-reading is cheaper than
            // preventing the collision.
            log.debug("Lost the race to create notification preferences for user {}, re-reading", user.getId());
            return repository.findById(user.getId())
                    .orElseThrow(() -> raced);
        }
    }

    /** Flips the engagement-mail switch for an authenticated account. */
    @Transactional
    public NotificationPreferences setEngagementEmail(User user, boolean enabled) {
        NotificationPreferences preferences = getOrCreate(user);
        preferences.setEngagementEmail(enabled);
        return repository.save(preferences);
    }

    /**
     * Turns engagement mail off for whoever holds this token.
     *
     * <p>Idempotent by construction: unsubscribing an already-unsubscribed account
     * writes the same value and reports the same success. Mail clients prefetch links
     * and people click twice, and neither should produce an error page.
     *
     * <p>Returns false only when the token matches nothing. The caller may say so —
     * there is no account to enumerate here, because the token is random rather than
     * derived from the address, so a wrong guess reveals nothing about who exists.
     */
    @Transactional
    public boolean unsubscribeByToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        return repository.findByUnsubscribeToken(token)
                .map(preferences -> {
                    preferences.setEngagementEmail(false);
                    repository.save(preferences);
                    // The id, never the token: this line ends up in a log shipper, and a
                    // capability in a log file is a capability anyone with log access holds.
                    log.info("Engagement mail disabled by unsubscribe link for user {}", preferences.getUserId());
                    return true;
                })
                .orElse(false);
    }

    private static String generateToken() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        return TOKEN_ENCODER.encodeToString(randomBytes);
    }
}
