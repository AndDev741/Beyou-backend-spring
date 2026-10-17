package beyou.beyouapp.backend.domain.aiAgent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentSegment;
import beyou.beyouapp.backend.domain.aiAgent.dto.AgentEvent;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;

/**
 * Turns the ordered event stream into a structured transcript. Every event
 * (tokens via the Flux, tools via MeteredToolCallback) passes through the same
 * send consumer in causal order — the stream suspends while a tool runs — so
 * observing here yields text interleaved with the tools exactly as they happened.
 * Only finished tools are recorded; "started" is a live-only affordance.
 */
class AgentTurnBuilder {

    private final List<AgentSegment> segments = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    @SuppressWarnings("unchecked")
    void observe(AgentEvent event) {
        switch (event.type()) {
            case "token" -> text.append(String.valueOf(event.data().getOrDefault("text", "")));
            case "tool" -> {
                if ("finished".equals(event.data().get("status"))) {
                    flushText();
                    segments.add(AgentSegment.tool(
                            (String) event.data().get("tool"),
                            (String) event.data().get("error"),
                            (List<String>) event.data().get("domains"),
                            (DiagramSpec) event.data().get("diagram"),
                            (UUID) event.data().get("pageId"),
                            (String) event.data().get("pageTitle")));
                }
            }
            default -> { /* done/error are not part of the transcript */ }
        }
    }

    private void flushText() {
        if (text.length() > 0) {
            segments.add(AgentSegment.text(text.toString()));
            text.setLength(0);
        }
    }

    List<AgentSegment> build() {
        flushText();
        return List.copyOf(segments);
    }
}
