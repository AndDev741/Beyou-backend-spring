package beyou.beyouapp.backend.domain.notebook.diagram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** One link of a {@link DiagramSpec}, between two node ids. Top level so the API schema is named DiagramLink. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiagramLink(
        @JsonPropertyDescription("Id of the node the link starts at")
        String from,
        @JsonPropertyDescription("Id of the node the link ends at")
        String to,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional, at most 40 characters: the condition or the message")
        String label,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional. A dashed line: in SEQUENCE a reply, elsewhere something optional or indirect")
        Boolean dashed) {
}
