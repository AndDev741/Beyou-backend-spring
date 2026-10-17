package beyou.beyouapp.backend.domain.notebook.diagram;

import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

/**
 * A diagram as a JSON string and back, for the places that store one as text: the editor
 * block's {@code spec} prop ({@code MarkdownBlocks}) and a study-chat answer's {@code diagram}
 * column. Static, like the mapper in MarkdownBlocks and BlockText, because those are static too.
 */
public final class DiagramJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private DiagramJson() {}

    public static String write(DiagramSpec spec) {
        return MAPPER.writeValueAsString(spec);
    }

    /**
     * The stored diagram, cleaned again, or empty when there is none or it does not parse. A
     * stored diagram that cannot be read is dropped from the answer rather than failing the read
     * of the whole conversation it sits in.
     */
    public static Optional<DiagramSpec> read(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return DiagramSanitizer.clean(MAPPER.readValue(json, DiagramSpec.class));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
