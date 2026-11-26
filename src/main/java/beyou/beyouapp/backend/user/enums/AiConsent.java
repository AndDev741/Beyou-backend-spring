package beyou.beyouapp.backend.user.enums;

/**
 * The person's answer to "may Beyou send what you write to its AI provider?".
 *
 * <p>Asked once by the phone app before the first AI feature runs (App Store guideline
 * 5.1.2(i) wants explicit permission before personal data goes to a third-party AI), and
 * changeable at any time in Configuration. Mirrored by the CHECK constraint in V48.
 *
 * <ul>
 *   <li>{@link #UNASKED} — nobody asked yet. Every account created before this existed, and
 *       every web-only account, sits here. The AI features keep working as they always did,
 *       so the web is unchanged; the phone asks before it lets anything AI run.</li>
 *   <li>{@link #GRANTED} — the person said yes.</li>
 *   <li>{@link #DECLINED} — the person said no. {@code AiConsentInterceptor} refuses every
 *       route that would reach the provider, and the daily briefing is served without its
 *       prose, on every client.</li>
 * </ul>
 */
public enum AiConsent {
    UNASKED,
    GRANTED,
    DECLINED
}
