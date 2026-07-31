package beyou.beyouapp.backend.domain.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * R4 — the context captured automatically alongside a submission. The client
 * fills every field from what it already knows; the user never types any of
 * it, and a missing field never blocks a submission (R6).
 *
 * Column widths match {@code V9__feedback.sql} and the DTO's {@code @Size}
 * caps — values are clamped in {@link FeedbackMapper} so an oversized captured
 * value can never fail a submission the user did nothing wrong in.
 */
@Embeddable
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class FeedbackContext {

    public static final int SCREEN_MAX = 200;
    public static final int APP_VERSION_MAX = 40;
    public static final int PLATFORM_MAX = 40;
    public static final int LANGUAGE_MAX = 20;
    public static final int THEME_MAX = 40;

    /** Originating screen — a route path such as {@code /routines}. */
    @Column(name = "context_screen", length = SCREEN_MAX)
    private String screen;

    @Column(name = "context_app_version", length = APP_VERSION_MAX)
    private String appVersion;

    /** {@code web}, {@code android}, {@code ios}. */
    @Column(name = "context_platform", length = PLATFORM_MAX)
    private String platform;

    /** UI language tag, e.g. {@code en} or {@code pt}. */
    @Column(name = "context_language", length = LANGUAGE_MAX)
    private String language;

    /** Active theme name, e.g. {@code beYouDark}. */
    @Column(name = "context_theme", length = THEME_MAX)
    private String theme;
}
