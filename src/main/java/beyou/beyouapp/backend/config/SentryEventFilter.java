package beyou.beyouapp.backend.config;

import beyou.beyouapp.backend.exceptions.BusinessException;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import org.springframework.stereotype.Component;

/**
 * Drops handled business outcomes before they are sent to the error collector.
 *
 * <p>{@link BusinessException} (and its subclasses — CategoryNotFound, UserNotFound,
 * GoalNotFound, …) is how this codebase expresses ordinary domain results: not found,
 * not owned, invalid input. {@code GlobalExceptionHandler} maps every one of them to a
 * 400 with an {@code ErrorKey}. They are normal traffic, not failures — a user asking
 * for a deleted habit is not an incident. Reporting them would bury the genuine
 * unhandled exceptions the collector exists to surface.
 *
 * <p>Sentry's autoconfiguration picks this bean up as the single
 * {@link SentryOptions.BeforeSendCallback} and runs it on every capture path (the MVC
 * exception resolver, the uncaught-exception handler, manual captures), so the
 * exclusion does not depend on Spring MVC resolver ordering. Returning {@code null}
 * discards the event.
 *
 * <p>The bean is harmless when telemetry is off: without a {@code sentry.dsn} no Sentry
 * autoconfiguration runs at all and this is just an unused POJO in the context.
 */
@Component
public class SentryEventFilter implements SentryOptions.BeforeSendCallback {

    /**
     * Guards against pathological or self-referential cause chains. Real chains in this
     * app are 1-3 deep; anything past this is not worth walking.
     */
    private static final int MAX_CAUSE_DEPTH = 20;

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        return isHandledBusinessOutcome(event.getThrowable()) ? null : event;
    }

    /**
     * True when the throwable is, or was caused by, a {@link BusinessException}. The
     * cause chain matters because framework layers (transaction proxies, the servlet
     * container) routinely re-wrap what a service threw.
     */
    static boolean isHandledBusinessOutcome(Throwable throwable) {
        Throwable current = throwable;

        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof BusinessException) {
                return true;
            }

            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }

        return false;
    }
}
