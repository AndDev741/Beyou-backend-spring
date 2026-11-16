package beyou.beyouapp.backend.domain.secondbrain;

import java.util.Locale;

/**
 * What the overview says first: whether the person is heading where they said they want to go.
 *
 * <p>A value of its own and not only a sentence, because the page shows it as a label. The
 * point of the page is to know this at a glance, and prose has to be read to be known.
 *
 * <p>Mirrored by a CHECK constraint in V45: adding a value here without adding it there makes
 * every write of it fail.
 */
public enum SecondBrainVerdict {
    /** What the person does lines up with what they said they want. */
    ON_COURSE,
    /** It does not, and the overview says which correction the data points at. */
    DRIFTING,
    /** Not enough recorded to say either. A new account, or a week with nothing in it. */
    TOO_EARLY;

    /**
     * What the model wrote, read leniently. Anything unrecognised is {@link #TOO_EARLY}: a
     * verdict the model did not clearly give must not be shown as one.
     */
    public static SecondBrainVerdict parse(String raw) {
        if (raw == null) {
            return TOO_EARLY;
        }
        String key = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (SecondBrainVerdict verdict : values()) {
            if (verdict.name().equals(key)) {
                return verdict;
            }
        }
        return TOO_EARLY;
    }
}
