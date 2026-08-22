package beyou.beyouapp.backend.unit.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Ticker;

import beyou.beyouapp.backend.monitoring.UserActivityTracker;
import beyou.beyouapp.backend.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Time is driven manually through the injected clock and ticker (the same instant
 * source feeds both) so the two behaviors under test — the 5-minute sliding "active"
 * window behind the gauge and the once-per-window throttle on {@code last_seen_at}
 * writes — are asserted at exact boundaries rather than with sleeps.
 */
class UserActivityTrackerTest {

    private static final Instant START = Instant.parse("2026-08-22T10:00:00Z");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AtomicLong elapsedNanos = new AtomicLong();

    private final Clock clock = new Clock() {
        @Override public Instant instant() { return START.plusNanos(elapsedNanos.get()); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    };
    private final Ticker ticker = elapsedNanos::get;

    private final UserActivityTracker tracker =
            new UserActivityTracker(userRepository, meterRegistry, clock, ticker);

    private void advance(Duration d) {
        elapsedNanos.addAndGet(d.toNanos());
    }

    private double activeUsersGauge() {
        return meterRegistry.get(UserActivityTracker.ACTIVE_USERS_METRIC).gauge().value();
    }

    @Test
    void aTouchedUserCountsAsActiveAndIsPersistedOnce() {
        UUID userId = UUID.randomUUID();

        tracker.touch(userId);
        tracker.touch(userId);

        assertEquals(1, activeUsersGauge());
        verify(userRepository, times(1)).recordSeen(eq(userId), any(Instant.class));
    }

    @Test
    void distinctUsersAreCountedSeparately() {
        tracker.touch(UUID.randomUUID());
        tracker.touch(UUID.randomUUID());

        assertEquals(2, activeUsersGauge());
    }

    @Test
    void aUserQuietForTheWholeWindowStopsCountingAsActive() {
        tracker.touch(UUID.randomUUID());

        advance(UserActivityTracker.ACTIVITY_WINDOW.plusSeconds(1));

        assertEquals(0, activeUsersGauge());
    }

    /**
     * The window is SLIDING: each touch resets the expiry, so a user active for longer
     * than one window never flickers out of the gauge.
     */
    @Test
    void continuousActivityKeepsTheUserActivePastOneWindow() {
        UUID userId = UUID.randomUUID();

        tracker.touch(userId);
        advance(Duration.ofMinutes(4));
        tracker.touch(userId);
        advance(Duration.ofMinutes(4));

        // 8 minutes after the first touch, 4 after the last — still active.
        assertEquals(1, activeUsersGauge());
    }

    @Test
    void lastSeenIsWrittenAgainOnlyOnceTheWindowHasElapsed() {
        UUID userId = UUID.randomUUID();

        tracker.touch(userId);                       // writes (first sighting)
        advance(Duration.ofMinutes(4));
        tracker.touch(userId);                       // 4 min since write — throttled
        advance(Duration.ofMinutes(4));
        tracker.touch(userId);                       // 8 min since write — writes again

        verify(userRepository, times(2)).recordSeen(eq(userId), any(Instant.class));
    }

    @Test
    void recordLoginPersistsAndMarksTheUserActive() {
        UUID userId = UUID.randomUUID();

        tracker.recordLogin(userId);

        assertEquals(1, activeUsersGauge());
        verify(userRepository).recordLogin(userId, START);
    }

    /** Analytics must never fail the request it rides on. */
    @Test
    void aFailingDatabaseWriteIsSwallowed() {
        UUID userId = UUID.randomUUID();
        doThrow(new RuntimeException("db down")).when(userRepository).recordSeen(any(), any());
        doThrow(new RuntimeException("db down")).when(userRepository).recordLogin(any(), any());

        assertDoesNotThrow(() -> tracker.touch(userId));
        assertDoesNotThrow(() -> tracker.recordLogin(userId));
    }
}
