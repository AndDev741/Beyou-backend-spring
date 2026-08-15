package beyou.beyouapp.backend.security.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;

import lombok.extern.slf4j.Slf4j;

/**
 * How many times one account may be guessed at, regardless of where from.
 *
 * <p>The IP bucket in {@link RateLimitFilter} was the only automated-abuse control on
 * login, and an address is the wrong thing to lean on twice over. A client that could
 * choose its own forwarded header minted a fresh bucket per request, which is the bug
 * this ships beside the fix for; and even with the header corrected, a deployment
 * behind a tunnel sees one socket address for the whole internet, so an IP bucket there
 * either lets everyone through together or locks everyone out together.
 *
 * <p>An account is the thing actually under attack, so an account is what gets counted.
 * Credential stuffing walks a list of emails against a list of passwords, and this
 * makes the number of guesses per email finite no matter how the traffic is spread.
 *
 * <p>In memory, on purpose. A single-host beta gains nothing from a database round trip
 * on every login, and the failure mode of losing the counters is a restart handing an
 * attacker their attempts back — which is a far smaller problem than a login path that
 * writes to Postgres for every wrong password. It is the same trade the rate-limit
 * cache beside it already makes.
 *
 * <p>Keyed by the lowercased email, and it counts an unknown address the same as a
 * known one. Skipping the count for accounts that do not exist would turn the lockout
 * itself into an oracle: try six times and see whether you get "locked" or "wrong
 * password", and you have learned which emails are real.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class LoginAttemptService {

    private final Cache<String, Attempts> attempts;
    private final int maxAttempts;
    private final Duration lockout;

    public LoginAttemptService(
            @Value("${rate-limit.login.max-attempts:10}") int maxAttempts,
            @Value("${rate-limit.login.lockout-minutes:15}") int lockoutMinutes) {
        this.maxAttempts = maxAttempts;
        this.lockout = Duration.ofMinutes(lockoutMinutes);
        // Expiry from last write: a wrong password refreshes the window, so an attacker
        // grinding away never ages out of it while someone who mistyped once does.
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(this.lockout)
                .maximumSize(50_000)
                .build();
    }

    /** True when this account has spent its guesses and is inside the cool-off. */
    public boolean isLocked(String email) {
        Attempts current = attempts.getIfPresent(key(email));
        return current != null && current.count.get() >= maxAttempts;
    }

    /** Counts one wrong password. */
    public void recordFailure(String email) {
        String key = key(email);
        Attempts current = attempts.get(key, k -> new Attempts());
        int total = current.count.incrementAndGet();
        current.lastAt = Instant.now();
        if (total == maxAttempts) {
            // Worth a line in the log: this is what a stuffing run looks like from here.
            log.warn("Login locked for an account after {} failed attempts", total);
        }
    }

    /**
     * Forgets the account's failures.
     *
     * <p>Called on a correct password, so an ordinary person who mistyped four times
     * and then got it right starts clean rather than carrying the count until it ages
     * out.
     */
    public void recordSuccess(String email) {
        attempts.invalidate(key(email));
    }

    /** Lowercased, because addresses are not case sensitive and neither is the attack. */
    private String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Attempts {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant lastAt = Instant.now();
    }
}
