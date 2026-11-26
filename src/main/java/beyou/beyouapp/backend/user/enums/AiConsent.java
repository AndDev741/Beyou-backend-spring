package beyou.beyouapp.backend.user.enums;

/**
 * The person's answer to "may Beyou send what you write to its AI provider?".
 *
 * <p>Asked once, by the phone and by the web, before the first AI feature runs (App Store
 * guideline 5.1.2(i) wants explicit permission before personal data goes to a third-party AI),
 * and changeable at any time in Configuration. Mirrored by the CHECK constraint in V48.
 *
 * <p>Only {@link #GRANTED} lets anything reach the provider: {@code AiConsentInterceptor}
 * refuses every {@code @SendsToAiProvider} route otherwise, the daily briefing is served with
 * its facts and no prose, and the background work that was already accepted checks again
 * before each call ({@link beyou.beyouapp.backend.security.aiconsent.AiConsentPolicy}).
 *
 * <ul>
 *   <li>{@link #UNASKED} — nobody asked yet. Every account created before this existed.</li>
 *   <li>{@link #GRANTED} — the person said yes.</li>
 *   <li>{@link #DECLINED} — the person said no.</li>
 * </ul>
 */
public enum AiConsent {
    UNASKED,
    GRANTED,
    DECLINED
}
