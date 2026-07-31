package beyou.beyouapp.backend.unit.config;

import beyou.beyouapp.backend.config.SentryEventFilter;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.category.CategoryNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Proves the exclusion actually drops handled business outcomes rather than trusting
 * that the Sentry configuration is right. The callback is exercised directly — the same
 * object Sentry's autoconfiguration installs as the single BeforeSendCallback.
 */
class SentryEventFilterTest {

    private final SentryEventFilter filter = new SentryEventFilter();

    @Test
    void dropsEventForBusinessException() {
        SentryEvent event = new SentryEvent(new BusinessException(ErrorKey.INVALID_REQUEST, "bad input"));

        assertNull(filter.execute(event, new Hint()),
                "A handled BusinessException must never reach the collector");
    }

    @Test
    void dropsEventForBusinessExceptionSubclasses() {
        assertNull(filter.execute(new SentryEvent(new CategoryNotFound("no category")), new Hint()));
        assertNull(filter.execute(new SentryEvent(new UserNotFound("no user")), new Hint()));
    }

    @Test
    void dropsEventWhenBusinessExceptionIsNestedAsCause() {
        Throwable wrapped = new IllegalStateException("proxy wrapper",
                new RuntimeException("outer", new CategoryNotFound("no category")));

        assertNull(filter.execute(new SentryEvent(wrapped), new Hint()),
                "Framework layers re-wrap service exceptions; the cause chain must be walked");
    }

    @Test
    void keepsEventForGenuineUnhandledException() {
        SentryEvent event = new SentryEvent(new NullPointerException("real bug"));

        assertSame(event, filter.execute(event, new Hint()),
                "Unhandled server errors are exactly what the collector is for");
    }

    @Test
    void keepsEventWithNoThrowable() {
        SentryEvent event = new SentryEvent();

        assertNotNull(filter.execute(event, new Hint()));
    }

    @Test
    void keepsEventsLoggedBySelfHandlingCodeThatSwallowsItsOwnFailures() {
        SentryEvent fromEmailListener = new SentryEvent(new IllegalStateException("SMTP is down"));
        fromEmailListener.setLogger("beyou.beyouapp.backend.notification.EmailService");

        assertSame(fromEmailListener, filter.execute(fromEmailListener, new Hint()),
                "a swallowed async failure reaches the collector through its ERROR log or not at all");
    }

    @Test
    void doesNotLoopOnSelfReferentialCauseChain() {
        Throwable selfCausing = new RuntimeException("boom") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        SentryEvent event = new SentryEvent(selfCausing);

        assertSame(event, filter.execute(event, new Hint()));
    }
}
