package beyou.beyouapp.backend.unit.config;

import beyou.beyouapp.backend.config.SentryEventFilter;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.BusinessException;
import io.sentry.Hint;
import io.sentry.IScopes;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryId;
import io.sentry.spring7.SentryExceptionResolver;
import io.sentry.spring7.tracing.SpringServletTransactionNameProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the capture path without booting the stack or a collector.
 *
 * <p>Drives the SDK's real {@link SentryExceptionResolver} — the component Sentry's
 * autoconfiguration registers to catch exceptions Spring MVC could not resolve — and
 * feeds the event it produces into the real {@link SentryEventFilter}. That covers both
 * halves of the requirement in one pass: an unhandled exception survives to the
 * collector, a handled {@link BusinessException} does not.
 *
 * <p>This also pins down a non-obvious detail: the resolver wraps the exception in an
 * {@code ExceptionMechanismException} before building the event, so a filter reading the
 * raw field instead of {@code SentryEvent#getThrowable()} would silently stop matching.
 */
class SentryCapturePathTest {

    private final SentryEventFilter filter = new SentryEventFilter();

    private SentryEvent captureEventFor(Exception thrown) {
        IScopes scopes = mock(IScopes.class);
        when(scopes.captureEvent(any(SentryEvent.class), any(Hint.class)))
                .thenReturn(new SentryId());

        SentryExceptionResolver resolver = new SentryExceptionResolver(
                scopes, new SpringServletTransactionNameProvider(), Ordered.LOWEST_PRECEDENCE);

        resolver.resolveException(
                new MockHttpServletRequest("POST", "/api/v1/habit"),
                new MockHttpServletResponse(),
                null,
                thrown);

        ArgumentCaptor<SentryEvent> captured = ArgumentCaptor.forClass(SentryEvent.class);
        verify(scopes).captureEvent(captured.capture(), any(Hint.class));
        return captured.getValue();
    }

    @Test
    void unhandledExceptionIsCapturedAndSurvivesTheFilter() {
        SentryEvent event = captureEventFor(new IllegalStateException("database is on fire"));

        assertNotNull(event, "an unhandled server exception must reach the capture path");
        assertNotNull(filter.execute(event, new Hint()),
                "and must not be filtered out — this is exactly what the collector is for");
    }

    @Test
    void businessExceptionReachingTheResolverIsStillDropped() {
        SentryEvent event = captureEventFor(new BusinessException(ErrorKey.HABIT_NOT_FOUND, "no habit"));

        assertNull(filter.execute(event, new Hint()),
                "even if a handled business outcome reaches the resolver, it must never be sent");
    }
}
