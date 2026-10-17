package beyou.beyouapp.backend.domain.notebook.diagram;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A diagram as data, version 1. The same record is the wire format, the editor block's
 * {@code spec} prop, the model's structured answer and the agent tools' parameter, so its JSON
 * must match {@code @beyou/types/notebook/diagram} field for field.
 *
 * <p>The model never writes SVG, HTML or Mermaid. It fills this, {@link DiagramSanitizer} cleans
 * it, and the clients lay it out and draw it with theme colors. Nothing here is trusted until it
 * has been through the sanitizer: every field may be missing, blank or too long.
 *
 * <p>The descriptions are what the model reads, in the schema Spring AI sends with a structured
 * answer and with a tool's parameters. Optional fields say so ({@code required = false}), so the
 * schema does not push a model into inventing a detail for every node.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiagramSpec(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Format version. Always 1")
        Integer v,
        @JsonPropertyDescription("FLOW: steps and decisions joined by links. MAP: a tree, links go parent to child. "
                + "CYCLE: nodes in order around a circle. SEQUENCE: nodes are participants, links are messages in "
                + "order. LAYERS: nodes are bands from top to bottom. TIMELINE: nodes are events in order")
        DiagramKind kind,
        @JsonPropertyDescription("A short title, at most 80 characters")
        String title,
        @JsonPropertyDescription("2 to 24 nodes, usually 3 to 12. Their order matters for every kind but FLOW")
        List<DiagramNode> nodes,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Links between node ids. Ignored by CYCLE, LAYERS and TIMELINE")
        List<DiagramLink> links) {

    public static final int VERSION = 1;
}
