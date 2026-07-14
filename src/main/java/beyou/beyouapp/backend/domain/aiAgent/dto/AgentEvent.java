package beyou.beyouapp.backend.domain.aiAgent.dto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentSegment;

public record AgentEvent(
        String type,
        Map<String, Object> data) {

    public static AgentEvent token(String text) {
        return new AgentEvent("token", Map.of("text", text));
    }

    public static AgentEvent toolStarted(String name) {
        return new AgentEvent("tool", Map.of("tool", name, "status", "started"));
    }

    public static AgentEvent toolFinished(String name, String error, List<String> domains) {
        Map<String, Object> data = new HashMap<>(
                Map.of("tool", name, "status", "finished", "domains", domains));
        if (error != null)
            data.put("error", error);
        return new AgentEvent("tool", data);
    }

    /** Authoritative structured turn — the client swaps its live-built segments for this. */
    public static AgentEvent done(List<AgentSegment> segments) {
        return new AgentEvent("done", Map.of("segments", segments));
    }

    public static AgentEvent error(String errorKey) {
        return new AgentEvent("error", Map.of("error", errorKey));
    }
}
