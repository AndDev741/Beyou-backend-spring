package beyou.beyouapp.backend.docs;

import org.springframework.stereotype.Component;

/**
 * Locale normalisation shared by every docs read service.
 *
 * <p>This lives outside the services on purpose: the same normalisation has to run
 * in two places that cannot call each other. The method body needs it to pick the
 * right translation, and the {@code @Cacheable} key expression needs it so that
 * {@code ?locale=EN}, {@code ?locale=en} and a missing param don't each get their
 * own cache entry for an identical result. A private helper is invisible to SpEL,
 * and a self-invocation would bypass the caching proxy anyway.
 *
 * <p>Keying on the RAW param was also a 400 on every list endpoint: {@code key = "#locale"}
 * evaluates to null when the param is omitted, and Spring rejects a null cache key
 * ("Null key returned for cache operation"). {@code locale} is declared
 * {@code @RequestParam(required = false)}, so a bare GET — a crawler, a pasted link —
 * used to fail. Detail endpoints happened to survive it only because their keys
 * concatenate strings, which turns null into the literal "null".
 *
 * <p><strong>Why the key expressions say {@code @docsLocale.normalize(#locale)} and not
 * {@code T(beyou.beyouapp.backend.docs.DocsLocale).normalize(#locale)}.</strong> The
 * {@code T(...)} form took the whole docs site down in production on 2026-09-09 with
 * {@code EL1005E: Type cannot be found}. Spring's cache layer keeps ONE shared
 * {@code StandardEvaluationContext} and copies its {@code TypeLocator} into every
 * per-call context; that locator is created lazily, on the first cache operation after
 * boot, from the context class loader of whichever thread happens to run it. Inside the
 * packaged jar the app's classes are only visible to Boot's {@code LaunchedClassLoader},
 * so if that first thread carries the system class loader (a common-pool worker, for
 * instance), every later {@code T(...)} lookup fails, on every thread, until restart.
 * It never showed locally or in e2e because those run the exploded classpath, where
 * the system loader sees everything. A bean reference resolves through the
 * {@code BeanFactory} and does not load classes at all, so the race cannot reach it.
 * {@code DocsCacheKeyClassLoaderIT} reproduces the pin and guards this.
 */
@Component("docsLocale")
public final class DocsLocale {
    public static final String DEFAULT_LOCALE = "en";

    /**
     * Never returns null — that is the whole point, see the class javadoc. Static so the
     * service bodies can call it without a field; SpEL still reaches it through the bean
     * reference, since its method resolver sees static methods on the target's class too.
     */

    public static String normalize(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }

        return locale.trim().toLowerCase();
    }
}
