package beyou.beyouapp.backend.config;

import beyou.beyouapp.backend.AOP.ControllerLogging;
import beyou.beyouapp.backend.AOP.ServiceMethodsLogging;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.routine.checks.CheckItemService;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.docs.api.imp.ApiDocsImportService;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportService;
import beyou.beyouapp.backend.docs.blog.imp.BlogDocsImportService;
import beyou.beyouapp.backend.docs.project.imp.ProjectDocsImportService;
import beyou.beyouapp.backend.exceptions.GlobalExceptionHandler;
import beyou.beyouapp.backend.monitoring.SnapshotJobHeartbeat;
import beyou.beyouapp.backend.user.GoogleIdTokenVerifierServiceImpl;
import beyou.beyouapp.backend.user.UserService;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The single place that decides which loggers are excluded from error telemetry.
 *
 * <p>Turning the Sentry Logback integration on makes every INFO+ log line a breadcrumb
 * and every ERROR line an event. That is the point — a captured exception arrives with
 * the trail that led to it, and a handled-and-logged failure (the async listeners in
 * {@code notification.EmailService} swallow their own exceptions so a dead SMTP server
 * never costs a user's feedback submission) stops being invisible. But two classes of log
 * line must not make the trip, and both are matched here by <em>logger name</em>:
 *
 * <ul>
 *   <li><b>Lines that interpolate content the collector must never hold</b> — user-authored
 *       text, a user's name, a third-party response body, or a secret. Matching the logger
 *       rather than the message text means a reworded or newly added line in the same class
 *       cannot silently reopen the hole.</li>
 *   <li><b>Lines that are pure instrumentation</b>, which would crowd the trail out.</li>
 * </ul>
 *
 * <p>Every entry is derived from {@code Class#getName()} because Lombok's {@code @Slf4j}
 * names the logger after the declaring class — so renaming or moving one of these classes
 * moves its exclusion with it instead of leaving a dead string behind.
 *
 * <p>The right long-term fix for most of the payload entries is the log lines themselves:
 * {@code log.info("[LOG] Deleting task => {}", taskToDelete)} should log an id, not an
 * entity whose {@code toString} carries user-authored text. Until that happens the
 * exclusion is what keeps the "no PII in the collector" guarantee true.
 */
public final class SentryLoggerExclusions {

    /**
     * Loggers whose INFO/WARN/ERROR lines never become breadcrumbs.
     *
     * <p><b>Third-party response bodies.</b> A breadcrumb carries the fully interpolated
     * message, and these lines interpolate text the app never inspects — an upstream is free
     * to echo back whatever it received:
     * <ul>
     *   <li>{@link GlobalExceptionHandler} — {@code "Upstream HTTP request failed: {} {}"}
     *       logs {@code ex.getResponseBodyAsString()}: an entire response body.</li>
     *   <li>The four {@code docs/**&#47;imp} import services — each logs
     *       {@code e.getMessage()} of a {@code RestClientException}, and
     *       {@code HttpStatusCodeException#getMessage} embeds the first 512 characters of
     *       the response body.</li>
     *   <li>{@link GoogleIdTokenVerifierServiceImpl} — logs {@code e.getMessage()} of a
     *       Google API client exception, which for a {@code GoogleJsonResponseException}
     *       is built from Google's response body.</li>
     * </ul>
     *
     * <p><b>Secrets.</b> {@link SnapshotJobHeartbeat} logs {@code e.toString()} of a
     * {@code RestClient} failure, and {@code ResourceAccessException}'s message quotes the
     * request URL — here the heartbeat endpoint, whose path contains the monitor's secret
     * UUID.
     *
     * <p><b>User content.</b> These classes interpolate whole entities, DTOs or the user's
     * own name:
     * <ul>
     *   <li>{@link GoalService} — {@code "[LOG] Creating Goal with DTO => {}"} logs the
     *       whole create request, including the user's goal name and description.</li>
     *   <li>{@link TaskService} — logs {@code Task} / {@code TaskGroup} /
     *       {@code DiaryRoutine} entities, whose {@code toString} carries task names.</li>
     *   <li>{@link CheckItemService} — logs {@code HabitGroup} / {@code TaskGroup} /
     *       {@code HabitGroupCheck} entities and {@code user.getName()}.</li>
     *   <li>{@link DiaryRoutineService} — logs {@code diaryRoutine.getName()}.</li>
     *   <li>{@link ScheduleService} — logs a {@code Schedule} entity and a routine name.</li>
     *   <li>{@link UserService} — logs {@code user.getName()}.</li>
     * </ul>
     *
     * <p><b>Instrumentation noise.</b> {@link ServiceMethodsLogging} emits three INFO lines
     * ({@code [START]}, {@code [PERFORMANCE]}, {@code [END]}) for <em>every</em> service
     * method and {@link ControllerLogging} one for every controller method. A scope keeps
     * only the most recent 100 breadcrumbs, and what those lines say — which method ran, in
     * what order — is already in the stack trace of the event they would be attached to.
     * Letting them in trades the domain lines that explain a failure for a call trace the
     * event already carries.
     */
    public static final Set<String> BREADCRUMB_EXCLUDED = namesOf(
            GlobalExceptionHandler.class,
            ApiDocsImportService.class,
            ArchitectureDocsImportService.class,
            BlogDocsImportService.class,
            ProjectDocsImportService.class,
            GoogleIdTokenVerifierServiceImpl.class,
            SnapshotJobHeartbeat.class,
            GoalService.class,
            TaskService.class,
            CheckItemService.class,
            DiaryRoutineService.class,
            ScheduleService.class,
            UserService.class,
            ServiceMethodsLogging.class,
            ControllerLogging.class);

    /**
     * There is deliberately no event-side equivalent of the set above.
     *
     * <p>The aspects log an unhandled exception at ERROR on its way out, so one fault is
     * captured three times: {@link ServiceMethodsLogging}, {@link ControllerLogging}, then
     * Spring MVC's resolver. Excluding those loggers from events by name is the obvious fix
     * and the wrong one — the SDK's deduplication registers the throwable in an event
     * processor that runs before {@code beforeSend}, so discarding the first capture would
     * suppress the later ones as well and nothing would be reported at all. Deduplication
     * already collapses the three captures into one issue; see {@code SentryEventFilter}.
     */
    private static Set<String> namesOf(Class<?>... loggingClasses) {
        return Stream.of(loggingClasses).map(Class::getName).collect(Collectors.toUnmodifiableSet());
    }

    private SentryLoggerExclusions() {
    }
}
