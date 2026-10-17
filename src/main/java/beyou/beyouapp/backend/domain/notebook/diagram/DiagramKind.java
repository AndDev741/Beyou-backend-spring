package beyou.beyouapp.backend.domain.notebook.diagram;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * How a diagram's nodes are drawn. The order of the nodes in the spec means something for every
 * kind but FLOW; the clients lay each kind out by its own rules (see the contract in
 * docs/superpowers/plans/2026-10-17-notebook-diagrams.md, section 1).
 */
public enum DiagramKind {
    /** A directed graph over the links, layered automatically. */
    FLOW,
    /** A tree: links go parent to child, unreached nodes hang off the root. */
    MAP,
    /** Nodes in order around a circle, the last back to the first. */
    CYCLE,
    /** Nodes are participants, links are messages in order; dashed is a reply. */
    SEQUENCE,
    /** Nodes are bands from top to bottom. Links are ignored. */
    LAYERS,
    /** Nodes are events in order; detail holds the date. Links are ignored. */
    TIMELINE;

    /**
     * Most nodes this kind keeps. Past these a circle, a stack of bands, a row of events or a
     * row of participants stops fitting a phone screen.
     */
    public int maxNodes() {
        return switch (this) {
            case CYCLE, LAYERS -> 10;
            case TIMELINE -> 12;
            case SEQUENCE -> 6;
            case FLOW, MAP -> DiagramSanitizer.MAX_NODES;
        };
    }

    /**
     * Jackson entry point, for request bodies, model answers and tool arguments alike. Any case
     * is accepted, and anything that is not a kind reads as null instead of failing the whole
     * spec: the sanitizer turns a missing kind into FLOW, and a request's null kind means "the AI
     * picks". A free-tier model writing "TREE" should cost a default, not a retry.
     */
    @JsonCreator
    public static DiagramKind fromJson(String value) {
        if (value == null) return null;
        String wanted = value.strip().toUpperCase(Locale.ROOT);
        for (DiagramKind kind : values()) {
            if (kind.name().equals(wanted)) return kind;
        }
        return null;
    }
}
