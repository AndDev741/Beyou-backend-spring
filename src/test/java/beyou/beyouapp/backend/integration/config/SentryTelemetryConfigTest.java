package beyou.beyouapp.backend.integration.config;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.config.SentryBreadcrumbFilter;
import beyou.beyouapp.backend.config.SentryEventFilter;
import ch.qos.logback.classic.Logger;
import io.sentry.Sentry;
import io.sentry.SentryOptions;
import io.sentry.spring.boot4.SentryProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the telemetry configuration where it is load-bearing.
 *
 * <p>A DSN is injected on purpose. Without one, Sentry's
 * {@code @ConditionalOnProperty("sentry.dsn")} autoconfiguration never engages and every
 * assertion here would pass vacuously. Forcing the DSN in reproduces the dangerous case
 * — a {@code SENTRY_DSN} exported in a developer shell or on a CI runner leaking into
 * the suite — and proves the test profile still emits nothing.
 */
@SpringBootTest(properties = "sentry.dsn=https://publicKey@localhost:1/1")
class SentryTelemetryConfigTest extends AbstractIntegrationTest {

    @Autowired
    private SentryProperties sentryProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void reportingIsDisabledUnderTheTestProfileEvenWithADsnPresent() {
        assertFalse(sentryProperties.isEnabled(),
                "application-test.yml must switch Sentry off");
        assertFalse(Sentry.isEnabled(),
                "Sentry.init must be a no-op in tests — the suite emits nothing");
    }

    @Test
    void personallyIdentifyingDataCollectionIsOff() {
        assertFalse(sentryProperties.isSendDefaultPii(),
                "send-default-pii must stay false so credentials/headers/IPs are stripped");
        assertEquals(SentryOptions.RequestSize.NONE, sentryProperties.getMaxRequestBodySize(),
                "request bodies carry user-written content and must never be captured");
    }

    @Test
    void loggingIntegrationIsOffUnderTheTestProfile() {
        assertFalse(sentryProperties.getLogging().isEnabled(),
                "application-test.yml must switch the Logback appender off — the suite emits nothing");
    }

    /**
     * The strong form of the assertion above. {@code sentry.logging.enabled: false} keeps
     * Sentry's {@code SentryLogbackAppenderAutoConfiguration} from contributing its
     * initializer, so no appender is ever attached to the root logger. Asserting on the
     * live Logback context proves it, instead of trusting the property to be honoured.
     */
    @Test
    void noSentryAppenderIsAttachedToTheRootLoggerInTests() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        assertNull(root.getAppender("SENTRY_APPENDER"),
                "a test run must not be able to log its way to the collector");
    }

    /**
     * These two levels are the whole point of the integration and are inherited from
     * application.yaml (application-test.yml overrides only {@code enabled}), so they can
     * be locked in from here.
     */
    @Test
    void breadcrumbsStartAtInfoAndEventsAtError() {
        assertEquals(Level.INFO, sentryProperties.getLogging().getMinimumBreadcrumbLevel(),
                "breadcrumbs are the trail of log lines leading to a captured exception");
        assertEquals(Level.ERROR, sentryProperties.getLogging().getMinimumEventLevel(),
                "a lower threshold would turn GlobalExceptionHandler's WARN — which logs a "
                        + "third-party response body — into an event of its own");
    }

    /**
     * With the Logback appender on, one unhandled exception is captured three times (both
     * logging aspects log it at ERROR, then the MVC resolver captures it). Deduplication is
     * what collapses that into a single issue, so it stops being a mere SDK default here.
     */
    @Test
    void deduplicationIsOnSoOneFaultIsOneIssue() {
        assertTrue(sentryProperties.isEnableDeduplication(),
                "the logging aspects report every unhandled exception twice before the "
                        + "resolver does; without deduplication one bug becomes three issues");
    }

    @Test
    void breadcrumbExclusionsAreWiredAsTheBeforeBreadcrumbCallback() {
        assertInstanceOf(SentryBreadcrumbFilter.class,
                applicationContext.getBean(SentryOptions.BeforeBreadcrumbCallback.class),
                "Sentry's autoconfiguration wires the single BeforeBreadcrumbCallback bean; "
                        + "SentryBreadcrumbFilter must be it or logged payloads would leak");
    }

    @Test
    void releaseIdentifierIsSetSoEventsTieToABuild() {
        assertNotNull(sentryProperties.getRelease());
        assertFalse(sentryProperties.getRelease().isBlank());
        assertFalse(sentryProperties.isUseGitCommitIdAsRelease(),
                "no GitProperties bean exists; the explicit release must be the only source");
    }

    @Test
    void handledBusinessExceptionsAreFilteredOutByTheBeforeSendCallback() {
        assertInstanceOf(SentryEventFilter.class,
                applicationContext.getBean(SentryOptions.BeforeSendCallback.class),
                "Sentry's autoconfiguration wires the single BeforeSendCallback bean; "
                        + "SentryEventFilter must be it or BusinessException would be reported");
    }

    @Test
    void handledExceptionsDoNotReachTheCapturingResolverFirst() {
        assertEquals(Integer.MAX_VALUE, sentryProperties.getExceptionResolverOrder(),
                "the capturing resolver must run after GlobalExceptionHandler resolves");
    }
}
