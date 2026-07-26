package beyou.beyouapp.backend.integration.config;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.config.SentryEventFilter;
import io.sentry.Sentry;
import io.sentry.SentryOptions;
import io.sentry.spring.boot4.SentryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void loggingIntegrationIsOffSoLoggedPayloadsCannotLeak() {
        assertFalse(sentryProperties.getLogging().isEnabled(),
                "the Logback appender would ship logged payloads as events/breadcrumbs");
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
