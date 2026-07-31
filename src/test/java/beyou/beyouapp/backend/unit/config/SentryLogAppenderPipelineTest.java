package beyou.beyouapp.backend.unit.config;

import beyou.beyouapp.backend.AOP.ControllerLogging;
import beyou.beyouapp.backend.AOP.ServiceMethodsLogging;
import beyou.beyouapp.backend.config.SentryBreadcrumbFilter;
import beyou.beyouapp.backend.config.SentryEventFilter;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.GlobalExceptionHandler;
import beyou.beyouapp.backend.notification.EmailService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.ITransportFactory;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.logback.SentryAppender;
import io.sentry.transport.ITransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the whole Logback→Sentry path in one pass: a real {@link SentryAppender} receives
 * a real Logback event, the real SDK applies the appender's level gating, its own event
 * processors and the app's {@link SentryEventFilter} / {@link SentryBreadcrumbFilter}, and
 * the assertions are made on what actually reaches the transport. Nothing is asserted about
 * configuration.
 *
 * <p>A DSN is set so the SDK is genuinely enabled; the transport is a mock, so the pipeline
 * runs for real and no envelope can leave the JVM.
 */
class SentryLogAppenderPipelineTest {

    /** Exposes the appender's protected {@code append}; nothing else is overridden. */
    private static final class ProbeAppender extends SentryAppender {
        void accept(LoggingEvent event) {
            append(event);
        }
    }

    /**
     * The live Logback context, not a bare {@code new LoggerContext()} — the appender reads
     * the event's MDC map, which needs a context with a real MDC adapter attached. Events go
     * to the appender directly, so nothing is written to the console.
     */
    private static final LoggerContext LOGGER_CONTEXT =
            (LoggerContext) LoggerFactory.getILoggerFactory();

    private final List<Breadcrumb> breadcrumbsKept = new ArrayList<>();
    private final List<Breadcrumb> breadcrumbsDropped = new ArrayList<>();

    private ITransport transport;
    private ProbeAppender appender;

    @BeforeEach
    void initSentryWithTheAppsOwnCallbacks() {
        SentryBreadcrumbFilter breadcrumbFilter = new SentryBreadcrumbFilter();

        transport = mock(ITransport.class);
        ITransportFactory transportFactory = mock(ITransportFactory.class);
        when(transportFactory.create(any(SentryOptions.class), any())).thenReturn(transport);

        SentryOptions options = new SentryOptions();
        options.setDsn("https://publicKey@localhost:1/1");
        options.setEnableExternalConfiguration(false);
        options.setEnableUncaughtExceptionHandler(false);
        options.setTransportFactory(transportFactory);
        options.setBeforeSend(new SentryEventFilter());
        options.setBeforeBreadcrumb((breadcrumb, hint) -> {
            Breadcrumb result = breadcrumbFilter.execute(breadcrumb, hint);
            (result == null ? breadcrumbsDropped : breadcrumbsKept).add(breadcrumb);
            return result;
        });

        Sentry.init(options);

        appender = new ProbeAppender();
        appender.setContext(LOGGER_CONTEXT);
    }

    @AfterEach
    void closeSentry() {
        Sentry.close();
    }

    private void log(Class<?> loggingClass, Level level, String template, Throwable throwable, Object... args) {
        appender.accept(new LoggingEvent(
                loggingClass.getName(),
                LOGGER_CONTEXT.getLogger(loggingClass),
                level,
                template,
                throwable,
                args));
    }

    private void assertEnvelopesSent(int expected) throws IOException {
        if (expected == 0) {
            verify(transport, never()).send(any(SentryEnvelope.class), any(Hint.class));
        } else {
            verify(transport, org.mockito.Mockito.times(expected))
                    .send(any(SentryEnvelope.class), any(Hint.class));
        }
    }

    @Test
    void anErrorLogBecomesAnEvent() throws IOException {
        // EmailService's async listeners swallow their own failures so a dead SMTP server
        // never costs a user's feedback submission — this log line is the only signal.
        log(EmailService.class, Level.ERROR, "Failed to send the acknowledgement for feedback {}",
                new IllegalStateException("Mail server connection failed"), "9f3c");

        assertEnvelopesSent(1);
    }

    @Test
    void anInfoLogBecomesABreadcrumbAndNotAnEvent() throws IOException {
        log(EmailService.class, Level.INFO, "Sending acknowledgement for feedback {}", null, "9f3c");

        assertEnvelopesSent(0);
        assertEquals(1, breadcrumbsKept.size(), "INFO is at the breadcrumb threshold");
    }

    @Test
    void theUpstreamResponseBodyLineProducesNoEventAndNoBreadcrumb() throws IOException {
        log(GlobalExceptionHandler.class, Level.WARN, "Upstream HTTP request failed: {} {}", null,
                "404 NOT_FOUND", "{\"error\":\"no such user\",\"email\":\"someone@example.com\"}");

        assertEnvelopesSent(0);
        assertEquals(1, breadcrumbsDropped.size(), "the appender did build a breadcrumb for it");
        assertTrue(breadcrumbsKept.isEmpty(), "and the filter dropped it before the scope kept it");
        assertTrue(breadcrumbsDropped.getFirst().getMessage().contains("someone@example.com"),
                "guard against a false pass: the dropped breadcrumb really carried the body");
    }

    @Test
    void aLoggedBusinessExceptionIsStillNotReported() throws IOException {
        log(EmailService.class, Level.ERROR, "something handled went wrong",
                new BusinessException(ErrorKey.HABIT_NOT_FOUND, "no habit"));

        assertEnvelopesSent(0);
    }

    /**
     * The double-reporting case. One exception out of a service is logged at ERROR by
     * {@code ServiceMethodsLogging}, logged at ERROR again by {@code ControllerLogging}, and
     * finally captured by Sentry's MVC exception resolver — three captures of one fault.
     */
    @Test
    void oneExceptionLoggedByBothAspectsAndCapturedByTheResolverSendsOneEvent() throws IOException {
        NullPointerException oneRealBug = new NullPointerException("habit was null");

        log(ServiceMethodsLogging.class, Level.ERROR, "[ERROR] Exception in method {}: {}",
                oneRealBug, "HabitService.editHabit", oneRealBug.getMessage());
        log(ControllerLogging.class, Level.ERROR, "[EXCEPTION] Exception in {}: {}",
                oneRealBug, "HabitController.edit", oneRealBug.getMessage());
        Sentry.captureEvent(new SentryEvent(oneRealBug)); // stands in for the MVC resolver

        assertEnvelopesSent(1);
    }

    /**
     * Pins down <em>why</em> the duplicates above are left to the SDK instead of being
     * dropped by logger name in {@link SentryEventFilter}: deduplication registers the
     * throwable in an event processor, which the SDK runs <em>before</em> {@code beforeSend}.
     * A {@code beforeSend} that discarded the first capture would therefore also cost the
     * second — the fault would vanish entirely rather than be reported once.
     */
    @Test
    void deduplicationIsDecidedBeforeBeforeSendSoTheFirstCaptureMustBeAllowedToWin()
            throws IOException {
        RuntimeException sameInstance = new RuntimeException("one fault, two captures");

        Sentry.captureEvent(new SentryEvent(sameInstance));
        Sentry.captureEvent(new SentryEvent(sameInstance));

        assertEnvelopesSent(1);
    }

    /** Two genuinely different faults must not be collapsed into one. */
    @Test
    void distinctExceptionsAreStillReportedSeparately() throws IOException {
        log(EmailService.class, Level.ERROR, "Failed to send the acknowledgement for feedback {}",
                new IllegalStateException("SMTP is down"), "9f3c");
        log(EmailService.class, Level.ERROR, "Failed to send the reply mail for feedback {}",
                new IllegalStateException("SMTP is down"), "7a21");

        assertEnvelopesSent(2);
    }
}
