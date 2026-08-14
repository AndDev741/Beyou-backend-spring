package beyou.beyouapp.backend.unit.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.user.User;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class UserDateResolverUnitTest {

    private Logger resolverLogger;
    private ListAppender<ILoggingEvent> logAppender;
    private User user;

    @BeforeEach
    void setup() {
        user = new User();
        user.setId(UUID.randomUUID());

        resolverLogger = (Logger) LoggerFactory.getLogger(UserDateResolver.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        resolverLogger.addAppender(logAppender);
    }

    @AfterEach
    void teardown() {
        resolverLogger.detachAppender(logAppender);
    }

    @Test
    void shouldResolveTheOwnersLocalDayWhenTheServerHasAlreadyRolledOver() {
        // 21:00 on 2026-08-09 in Sao Paulo (UTC-3) is already 00:00 on 2026-08-10 at UTC.
        Clock serverClock = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);
        user.setTimezone("America/Sao_Paulo");

        assertEquals(LocalDate.of(2026, 8, 9), UserDateResolver.today(user, serverClock));
    }

    @Test
    void shouldResolveTheOwnersLocalDayWhenTheOwnerIsAheadOfTheServer() {
        // 20:00 UTC on 2026-08-09 is already 08:00 on the 10th in Auckland (UTC+12).
        Clock serverClock = Clock.fixed(Instant.parse("2026-08-09T20:00:00Z"), ZoneOffset.UTC);
        user.setTimezone("Pacific/Auckland");

        assertEquals(LocalDate.of(2026, 8, 10), UserDateResolver.today(user, serverClock));
    }

    @Test
    void shouldKeepAOneTimeTaskAliveOnTheOwnersLocalDay() {
        // An LA user checks a one-time task at 18:00 local on the 9th; UTC has already turned
        // to the 10th. Cleanup asks "has the marked day passed for this owner?" — it has not.
        Clock serverClock = Clock.fixed(Instant.parse("2026-08-10T01:00:00Z"), ZoneOffset.UTC);
        user.setTimezone("America/Los_Angeles");

        LocalDate markedToDelete = LocalDate.of(2026, 8, 9);

        assertEquals(LocalDate.of(2026, 8, 9), UserDateResolver.today(user, serverClock));
        assertFalse(markedToDelete.isBefore(UserDateResolver.today(user, serverClock)),
                "The owner's day has not passed yet, so the task must survive");
        assertTrue(markedToDelete.isBefore(LocalDate.now(serverClock)),
                "Against the server's day it would already have been deleted — the bug this guards");
    }

    @Test
    void shouldIgnoreTheClocksOwnZoneAndUseOnlyItsInstant() {
        Clock clockInAnotherZone = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        user.setTimezone("America/Sao_Paulo");

        assertEquals(LocalDate.of(2026, 8, 9), UserDateResolver.today(user, clockInAnotherZone));
    }

    @Test
    void shouldFallBackToTheServerZoneWhenTimezoneIsNull() {
        user.setTimezone(null);

        assertEquals(ZoneId.systemDefault(), UserDateResolver.zoneOf(user));
        assertEquals(LocalDate.now(), assertDoesNotThrow(() -> UserDateResolver.today(user)));
        assertTrue(logAppender.list.isEmpty(), "A missing timezone is normal, not worth a warning");
    }

    @Test
    void shouldFallBackToTheServerZoneWhenTimezoneIsBlank() {
        user.setTimezone("   ");

        assertEquals(ZoneId.systemDefault(), UserDateResolver.zoneOf(user));
        assertEquals(LocalDate.now(), assertDoesNotThrow(() -> UserDateResolver.today(user)));
    }

    @Test
    void shouldFallBackToTheServerZoneAndWarnWhenTimezoneIsUnparseable() {
        user.setTimezone("Mars/Olympus_Mons");

        assertEquals(ZoneId.systemDefault(), assertDoesNotThrow(() -> UserDateResolver.zoneOf(user)));
        assertEquals(1, logAppender.list.size());
        assertEquals("WARN", logAppender.list.get(0).getLevel().toString());
        assertTrue(logAppender.list.get(0).getFormattedMessage().contains("Mars/Olympus_Mons"));
    }

    @Test
    void shouldFallBackToTheServerZoneWhenThereIsNoUser() {
        assertEquals(ZoneId.systemDefault(), UserDateResolver.zoneOf(null));
        assertEquals(LocalDate.now(), assertDoesNotThrow(() -> UserDateResolver.today(null)));
    }

    @Test
    void shouldResolveTodayAgainstTheSystemClockForAValidZone() {
        user.setTimezone("Asia/Tokyo");

        assertEquals(LocalDate.now(ZoneId.of("Asia/Tokyo")), UserDateResolver.today(user));
    }
}
