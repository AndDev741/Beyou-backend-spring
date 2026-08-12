package beyou.beyouapp.backend.docs;

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
 */
public final class DocsLocale {
    public static final String DEFAULT_LOCALE = "en";

    private DocsLocale() {
    }

    /**
     * Never returns null — that is the whole point, see the class javadoc.
     */
    public static String normalize(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }

        return locale.trim().toLowerCase();
    }
}
