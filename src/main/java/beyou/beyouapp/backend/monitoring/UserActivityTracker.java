package beyou.beyouapp.backend.monitoring;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.user.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Product analytics phase 1: who is using the app right now, and when each account
 * was last here.
 *
 * <p>Two outputs from one signal ({@link #touch} on every authenticated request):
 * <ul>
 *   <li>The {@code beyou.active.users} gauge — users seen within the last
 *       {@link #ACTIVITY_WINDOW}. Prometheus samples it, so "concurrent users now"
 *       is the current value and "peak today/this week/this month" is
 *       {@code max_over_time(beyou_active_users[1d|7d|30d])} in Grafana.</li>
 *   <li>{@code users.last_seen_at} / {@code users.last_login_at} (V22) — the
 *       SQL-queryable ground truth behind logins-per-day panels, independent of
 *       Prometheus retention.</li>
 * </ul>
 *
 * <p><b>Fail-open by construction</b>, like {@link SnapshotJobHeartbeat}: analytics must
 * never be the reason a request fails, so every public method swallows and WARN-logs.
 * The repository methods carry their own {@code @Transactional} so that catch happens
 * outside the transaction proxy — see the note on {@code UserRepository.recordLogin}.
 *
 * <p><b>Write throttling.</b> A user in active use touches this on every request; writing
 * {@code last_seen_at} each time would turn the busiest read paths into writers. The cache
 * entry remembers when the last write for that user happened and only writes again once
 * the window has elapsed, so {@code last_seen_at} is stale by at most one window — an
 * error the "is this account still around" questions it serves cannot notice. Entries
 * expire {@code ACTIVITY_WINDOW} after their last touch ({@code put} resets the clock),
 * which is exactly the gauge's sliding-window definition of "active".
 */
@Component
@Slf4j
public class UserActivityTracker {

    public static final Duration ACTIVITY_WINDOW = Duration.ofMinutes(5);
    public static final String ACTIVE_USERS_METRIC = "beyou.active.users";

    private final UserRepository userRepository;
    private final Clock clock;

    /** userId → instant of the last persisted {@code last_seen_at} write for that user. */
    private final Cache<UUID, Instant> activeUsers;

    @Autowired
    public UserActivityTracker(UserRepository userRepository, MeterRegistry meterRegistry) {
        this(userRepository, meterRegistry, Clock.systemUTC(), Ticker.systemTicker());
    }

    /** Injection seam for tests, which drive time through the clock and ticker. */
    public UserActivityTracker(UserRepository userRepository, MeterRegistry meterRegistry,
                               Clock clock, Ticker ticker) {
        this.userRepository = userRepository;
        this.clock = clock;
        this.activeUsers = Caffeine.newBuilder()
                .expireAfterWrite(ACTIVITY_WINDOW)
                .ticker(ticker)
                .build();

        // cleanUp() before sizing: Caffeine expires lazily, and a scrape is exactly the
        // moment a lingering expired entry would be read as a still-active user.
        Gauge.builder(ACTIVE_USERS_METRIC, activeUsers, cache -> {
                    cache.cleanUp();
                    return cache.estimatedSize();
                })
                .description("Users who made an authenticated request within the last 5 minutes")
                .register(meterRegistry);
    }

    /** Called by {@code SecurityFilter} for every authenticated request. Never throws. */
    public void touch(UUID userId) {
        try {
            Instant now = clock.instant();
            Instant lastWrite = activeUsers.getIfPresent(userId);
            if (lastWrite == null || !lastWrite.plus(ACTIVITY_WINDOW).isAfter(now)) {
                userRepository.recordSeen(userId, now);
                lastWrite = now;
            }
            activeUsers.put(userId, lastWrite);
        } catch (Exception e) {
            log.warn("User activity tracking skipped: {}", e.toString());
        }
    }

    /**
     * Called by {@code RefreshTokenService.createRefreshToken} — the one method every
     * session-issuing path (password login, Google web, Google mobile, refresh) already
     * goes through, so no login path can forget it. Never throws.
     */
    public void recordLogin(UUID userId) {
        try {
            Instant now = clock.instant();
            userRepository.recordLogin(userId, now);
            activeUsers.put(userId, now);
        } catch (Exception e) {
            log.warn("Login activity recording skipped: {}", e.toString());
        }
    }
}
