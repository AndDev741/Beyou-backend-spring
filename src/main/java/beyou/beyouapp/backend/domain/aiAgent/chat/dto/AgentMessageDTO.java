package beyou.beyouapp.backend.domain.aiAgent.chat.dto;

import java.util.List;

/** One rendered turn: USER or ASSISTANT, as an ordered list of segments. */
public record AgentMessageDTO(String role, List<AgentSegment> segments) {
}
