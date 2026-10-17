package beyou.beyouapp.backend.domain.aiAgent.chat.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;

/**
 * One piece of an assistant turn: either a run of text or a single tool the
 * agent used. Ordered lists of these ARE the display transcript — same shape
 * on the wire (done event), persisted in agent_message.content, and rendered.
 * Null fields are dropped from JSON so each segment only carries its own kind.
 *
 * <p>{@code diagram}, {@code pageId} and {@code pageTitle} are what a tool showed
 * the user (ToolDisplay): showDiagram sets the diagram, addStudyDiagram all three.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentSegment(
        String type,
        String text,
        String tool,
        /** null = success; the frontend keys its red/green state on presence. */
        String error,
        List<String> domains,
        DiagramSpec diagram,
        UUID pageId,
        String pageTitle) {

    public static AgentSegment text(String text) {
        return new AgentSegment("text", text, null, null, null, null, null, null);
    }

    public static AgentSegment tool(String tool, String error, List<String> domains) {
        return tool(tool, error, domains, null, null, null);
    }

    public static AgentSegment tool(String tool, String error, List<String> domains, DiagramSpec diagram,
            UUID pageId, String pageTitle) {
        return new AgentSegment("tool", null, tool, error, domains, diagram, pageId, pageTitle);
    }
}
