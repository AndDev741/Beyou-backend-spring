package beyou.beyouapp.backend.user;

import java.util.Locale;
import java.util.Set;

/**
 * Which language an account reads Beyou in, decided in one place.
 *
 * <p>Two questions go through here. What a client claims at signup ({@link #usableOrNull}),
 * and what language a model should write in for this person ({@link #forPrompt}). They used
 * to be answered at each call site, and the answers drifted: the briefing treated a blank
 * value as missing while the notebook tutor passed the blank straight to the model, and
 * nothing at signup wrote the column at all, so every account that never opened the
 * language setting read its AI text in English whatever the screen around it said.
 *
 * <p>Only the primary subtag matters. Browsers report {@code pt-BR}, devices report
 * {@code pt}, and the app ships one Portuguese, which is Brazilian.
 */
public final class UserLanguage {

    public static final String DEFAULT = "en";

    private static final Set<String> SUPPORTED = Set.of("en", "pt");

    private UserLanguage() {}

    /**
     * The supported language a client claim stands for, or null when it stands for none.
     *
     * <p>Null rather than the default on purpose. A claim the server cannot use leaves the
     * account empty, and the client's boot reconcile gets another go at it, the same way an
     * unusable timezone claim is dropped.
     */
    public static String usableOrNull(String claimed) {
        if (claimed == null || claimed.isBlank()) {
            return null;
        }
        String primary = claimed.trim().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return SUPPORTED.contains(primary) ? primary : null;
    }

    /** {@link #usableOrNull}, falling back to English. */
    public static String orDefault(String language) {
        String usable = usableOrNull(language);
        return usable != null ? usable : DEFAULT;
    }

    /** The language every prompt sent on this person's behalf asks the model to write in. */
    public static String forPrompt(User user) {
        return orDefault(user != null ? user.getLanguageInUse() : null);
    }
}
