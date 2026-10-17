package beyou.beyouapp.backend.domain.notebook.diagram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** One node of a {@link DiagramSpec}. Top level so the API schema is named DiagramNode. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiagramNode(
        @JsonPropertyDescription("Short id the links point at, like n1")
        String id,
        @JsonPropertyDescription("A few words, at most 60 characters")
        String label,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional second line, at most 140 characters. Only when it adds something. "
                + "In LAYERS the right-hand text, in TIMELINE the date")
        String detail,
        @JsonProperty(required = false)
        @JsonPropertyDescription("NEUTRAL by default. ACCENT for the start or the key node, SUCCESS, WARNING, "
                + "DANGER for a failure or error path. Use sparingly")
        DiagramTone tone) {
}
