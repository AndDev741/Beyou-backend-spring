package beyou.beyouapp.backend.domain.notebook.diagram;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * What a node's color says. The clients map each tone to a theme color, so a diagram never
 * carries a color of its own and always matches the theme it is shown in.
 */
public enum DiagramTone {
    NEUTRAL,
    /** The start, or the one node the diagram is about. */
    ACCENT,
    SUCCESS,
    WARNING,
    /** A failure, an error path, something to avoid. */
    DANGER;

    /** Same leniency as {@link DiagramKind#fromJson}: unknown reads as null, which cleans to NEUTRAL. */
    @JsonCreator
    public static DiagramTone fromJson(String value) {
        if (value == null) return null;
        String wanted = value.strip().toUpperCase(Locale.ROOT);
        for (DiagramTone tone : values()) {
            if (tone.name().equals(wanted)) return tone;
        }
        return null;
    }
}
