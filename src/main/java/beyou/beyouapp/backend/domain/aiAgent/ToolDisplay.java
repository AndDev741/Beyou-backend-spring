package beyou.beyouapp.backend.domain.aiAgent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.model.ToolContext;

import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;

/**
 * What one tool call shows the user besides its name: today, the diagram it drew and the page it
 * went to. The model gets the tool's text result; the person gets this.
 *
 * <p>{@link MeteredToolCallback} makes a fresh one for every call and hands it to the tool
 * inside the ToolContext, then reads it when it emits the finished event, which is how the
 * diagram reaches the live SSE event and, through {@link AgentTurnBuilder}, the stored segment.
 * One per call rather than one per turn, so two tool calls can never see each other's diagram,
 * whatever order or thread they run on. A failed call's display is thrown away.
 */
public final class ToolDisplay {

    public static final String KEY = "toolDisplay";

    private DiagramSpec diagram;
    private UUID pageId;
    private String pageTitle;

    /** The display of the call this context belongs to, or null outside a metered call (unit tests). */
    public static ToolDisplay of(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object display = toolContext.getContext().get(KEY);
        return display instanceof ToolDisplay d ? d : null;
    }

    public void diagram(DiagramSpec diagram) {
        this.diagram = diagram;
    }

    public void page(UUID pageId, String pageTitle) {
        this.pageId = pageId;
        this.pageTitle = pageTitle;
    }

    /** The fields the finished event carries, only those that were set. */
    public Map<String, Object> fields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (diagram != null) fields.put("diagram", diagram);
        if (pageId != null) fields.put("pageId", pageId);
        if (pageTitle != null) fields.put("pageTitle", pageTitle);
        return fields;
    }
}
