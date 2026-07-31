package beyou.beyouapp.backend.config;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryOptions;
import org.springframework.stereotype.Component;

/**
 * Drops the log lines that must not become breadcrumbs.
 *
 * <p>With the Logback integration on, every INFO+ line is turned into a breadcrumb by
 * Sentry's {@code SentryAppender} and attached to the next captured event. The appender
 * sets the breadcrumb's <em>category</em> to the logger name and its <em>message</em> to
 * the fully interpolated line — so anything a log statement interpolates travels to the
 * collector. {@link SentryLoggerExclusions#BREADCRUMB_EXCLUDED} lists the loggers that
 * must not, and why each one is there.
 *
 * <p>Sentry's autoconfiguration picks this bean up as the single
 * {@link SentryOptions.BeforeBreadcrumbCallback} and runs it on every {@code addBreadcrumb}
 * path, so the exclusion does not depend on where the breadcrumb came from. Returning
 * {@code null} discards it.
 *
 * <p>The bean is harmless when telemetry is off: without a {@code sentry.dsn} no Sentry
 * autoconfiguration runs at all and this is just an unused POJO in the context.
 */
@Component
public class SentryBreadcrumbFilter implements SentryOptions.BeforeBreadcrumbCallback {

    @Override
    public Breadcrumb execute(Breadcrumb breadcrumb, Hint hint) {
        String logger = breadcrumb.getCategory();

        // A null category means the breadcrumb did not come from the Logback appender
        // (a manual Sentry.addBreadcrumb, an HTTP client integration): nothing to match.
        if (logger != null && SentryLoggerExclusions.BREADCRUMB_EXCLUDED.contains(logger)) {
            return null;
        }

        return breadcrumb;
    }
}
