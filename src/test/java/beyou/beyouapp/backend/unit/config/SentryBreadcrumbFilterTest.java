package beyou.beyouapp.backend.unit.config;

import beyou.beyouapp.backend.AOP.ControllerLogging;
import beyou.beyouapp.backend.AOP.ServiceMethodsLogging;
import beyou.beyouapp.backend.config.SentryBreadcrumbFilter;
import beyou.beyouapp.backend.exceptions.GlobalExceptionHandler;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.logback.SentryAppender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the breadcrumb exclusions actually drop the dangerous log lines, rather than
 * trusting that a logger name in a set matches what Logback produces at runtime.
 *
 * <p>Every breadcrumb here is built by the SDK's real
 * {@link SentryAppender#createBreadcrumb} from a real Logback {@link LoggingEvent}. That
 * pins down the two details the filter depends on: the breadcrumb's <em>category</em> is
 * the logger name, and its <em>message</em> is the fully interpolated line (so a logged
 * payload really would travel to the collector if it were not dropped).
 */
class SentryBreadcrumbFilterTest {

    /** Exposes the appender's protected factory methods; nothing else is overridden. */
    private static final class ProbeAppender extends SentryAppender {
        Breadcrumb breadcrumbFor(LoggingEvent event) {
            return createBreadcrumb(event);
        }
    }

    private static final LoggerContext LOGGER_CONTEXT =
            (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();

    private final SentryBreadcrumbFilter filter = new SentryBreadcrumbFilter();
    private final ProbeAppender appender = new ProbeAppender();

    private Breadcrumb breadcrumb(Class<?> loggingClass, Level level, String template, Object... args) {
        LoggingEvent event = new LoggingEvent(
                loggingClass.getName(),
                LOGGER_CONTEXT.getLogger(loggingClass),
                level,
                template,
                null,
                args);
        return appender.breadcrumbFor(event);
    }

    /**
     * The exact line from {@code GlobalExceptionHandler#handleHttpClientErrorException}.
     * A third-party error body is arbitrary text the app never inspects — it can carry
     * anything the upstream chose to echo back, including the request it received.
     */
    private Breadcrumb upstreamFailureBreadcrumb() {
        return breadcrumb(GlobalExceptionHandler.class, Level.WARN,
                "Upstream HTTP request failed: {} {}",
                "404 NOT_FOUND",
                "{\"error\":\"no such user\",\"email\":\"someone@example.com\"}");
    }

    @Test
    void dropsTheUpstreamResponseBodyBreadcrumb() {
        assertNull(filter.execute(upstreamFailureBreadcrumb(), new Hint()),
                "The upstream response body must never reach the collector as a breadcrumb");
    }

    @Test
    void theDroppedBreadcrumbReallyDidCarryTheResponseBody() {
        // Guard against a false pass: if the appender did not interpolate the body into
        // the breadcrumb message, the test above would be proving nothing.
        Breadcrumb unfiltered = upstreamFailureBreadcrumb();

        assertNotNull(unfiltered.getMessage());
        assertTrue(unfiltered.getMessage().contains("someone@example.com"),
                "the breadcrumb message is the interpolated line, body included");
        assertTrue(GlobalExceptionHandler.class.getName().equals(unfiltered.getCategory()),
                "the breadcrumb category is the logger name — the key the filter matches on");
    }

    @Test
    void dropsEveryBreadcrumbFromTheTranslationLayerNotJustThatOneMessage() {
        // Matching on the logger rather than the message text means a reworded or newly
        // added log line in the same @ControllerAdvice cannot reopen the hole.
        assertNull(filter.execute(
                breadcrumb(GlobalExceptionHandler.class, Level.WARN, "some future log line {}", "payload"),
                new Hint()));
    }

    @Test
    void dropsInstrumentationChatterFromTheLoggingAspects() {
        assertNull(filter.execute(
                breadcrumb(ServiceMethodsLogging.class, Level.INFO,
                        "[START] Starting method: {} with {} arg(s)", "editHabit", 2),
                new Hint()),
                "aspect chatter would evict the domain lines that actually explain a failure");
        assertNull(filter.execute(
                breadcrumb(ControllerLogging.class, Level.INFO,
                        "[REQUEST] {} - completed in {} ms", "HabitController.edit", 12),
                new Hint()));
    }

    @Test
    void dropsBreadcrumbsFromServicesThatLogWholeEntitiesOrDtos() {
        // These loggers interpolate entities/DTOs whose toString carries user-authored
        // habit/goal/task/routine names and the user's own name.
        assertNull(filter.execute(
                breadcrumb(beyou.beyouapp.backend.domain.goal.GoalService.class, Level.INFO,
                        "[LOG] Creating Goal with DTO => {}", "CreateGoalRequestDTO(name=quit smoking...)"),
                new Hint()));
        assertNull(filter.execute(
                breadcrumb(beyou.beyouapp.backend.domain.task.TaskService.class, Level.INFO,
                        "[LOG] Deleting task => {}", "Task(name=call the clinic)"),
                new Hint()));
        assertNull(filter.execute(
                breadcrumb(beyou.beyouapp.backend.user.UserService.class, Level.INFO,
                        "[SERVICE] marking date {} as complete for user {}", "2026-07-26", "Alice Doe"),
                new Hint()));
    }

    @Test
    void dropsBreadcrumbsFromTheOtherLinesThatLogAnUpstreamResponseBody() {
        // RestClientException#getMessage embeds the first 512 characters of the response
        // body for any HttpStatusCodeException, so these read the same as the line above.
        assertNull(filter.execute(
                breadcrumb(beyou.beyouapp.backend.docs.project.imp.ProjectDocsImportService.class,
                        Level.WARN, "Project docs GitHub fetch failed: {}",
                        "404 Not Found: \"{\\\"message\\\":\\\"Not Found\\\"}\""),
                new Hint()));
        assertNull(filter.execute(
                breadcrumb(beyou.beyouapp.backend.docs.api.imp.ApiDocsImportService.class,
                        Level.WARN, "API docs GitHub fetch failed: {}", "401 Unauthorized: \"...\""),
                new Hint()));
        assertNull(filter.execute(
                breadcrumb(beyou.beyouapp.backend.docs.blog.imp.BlogDocsImportService.class,
                        Level.WARN, "Blog docs GitHub fetch failed: {}", "500 : \"...\""),
                new Hint()));
        assertNull(filter.execute(
                breadcrumb(beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportService.class,
                        Level.WARN, "Architecture docs GitHub fetch failed: {}", "403 : \"...\""),
                new Hint()));
        assertNull(filter.execute(
                breadcrumb(beyou.beyouapp.backend.user.GoogleIdTokenVerifierServiceImpl.class,
                        Level.WARN, "Google ID token verification errored: {}",
                        "400 Bad Request GET https://oauth2.googleapis.com/tokeninfo: {...}"),
                new Hint()));
    }

    @Test
    void dropsTheHeartbeatFailureBreadcrumbBecauseItsMessageQuotesASecretUrl() {
        // ResourceAccessException's message quotes the request URL, and the heartbeat
        // endpoint's path is the monitor's secret UUID.
        assertNull(filter.execute(
                breadcrumb(beyou.beyouapp.backend.monitoring.SnapshotJobHeartbeat.class, Level.WARN,
                        "Snapshot job heartbeat could not be delivered: {}",
                        "org.springframework.web.client.ResourceAccessException: I/O error on POST "
                                + "request for \"http://glitchtip:8000/api/0/organizations/beyou/"
                                + "heartbeat_check/58354b3b-211d-4884-aeae-49c64f41ad5e/\""),
                new Hint()));
    }

    @Test
    void keepsBreadcrumbsThatCarryTheTrailWorthHaving() {
        Breadcrumb emailFailure = breadcrumb(beyou.beyouapp.backend.notification.EmailService.class, Level.ERROR,
                "Failed to send the acknowledgement for feedback {}", "9f3c");

        assertNotNull(filter.execute(emailFailure, new Hint()),
                "a failed async e-mail send is exactly the invisible failure this exists for");

        Breadcrumb llmFallback = breadcrumb(beyou.beyouapp.backend.domain.aiAgent.llm.FallbackChatModel.class,
                Level.WARN, "LLM provider '{}' failed ({}), falling back to next in chain", "gemini", "429");

        assertNotNull(filter.execute(llmFallback, new Hint()),
                "provider fallbacks are not recoverable from a stack trace — keep them");
    }

    @Test
    void keepsBreadcrumbsWithNoCategory() {
        assertNotNull(filter.execute(new Breadcrumb(), new Hint()),
                "a breadcrumb from a non-Logback source must pass through untouched");
    }

    @Test
    void excludedLoggersAreResolvedFromRealClassesSoARenameCannotSilentlyReopenTheHole() {
        assertFalse(beyou.beyouapp.backend.config.SentryLoggerExclusions.BREADCRUMB_EXCLUDED.isEmpty());
        assertTrue(beyou.beyouapp.backend.config.SentryLoggerExclusions.BREADCRUMB_EXCLUDED.stream()
                        .allMatch(name -> name.startsWith("beyou.beyouapp.backend.")),
                "exclusions must be derived from Class#getName, not hand-typed strings");
    }
}
