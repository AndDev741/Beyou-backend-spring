package beyou.beyouapp.backend.domain.aiAgent.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentMessageDTO;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the display transcript (agent_message): persists each turn as ordered
 * segments and reads it back for the UI. Falls back to the Spring AI memory
 * (text-only) for chats created before this table existed.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentMessageService {

    private static final String USER = "USER";
    private static final String ASSISTANT = "ASSISTANT";
    private static final List<String> VISIBLE_MEMORY_ROLES = List.of("USER", "ASSISTANT");

    private final AgentMessageRepository agentMessageRepository;
    private final ChatMemory chatMemory;
    private final ObjectMapper objectMapper;

    /** Persist one user->assistant exchange as two ordered rows. */
    @Transactional
    public void recordTurn(UUID chatId, String userInput, List<AgentSegment> assistantSegments) {
        long seq = agentMessageRepository.countByChatId(chatId);
        agentMessageRepository.save(new AgentMessage(
                chatId, USER, toJson(List.of(AgentSegment.text(userInput))), seq));
        agentMessageRepository.save(new AgentMessage(
                chatId, ASSISTANT, toJson(assistantSegments), seq + 1));
    }

    @Transactional(readOnly = true)
    public List<AgentMessageDTO> getMessages(UUID chatId) {
        List<AgentMessage> stored = agentMessageRepository.findByChatIdOrderBySequenceIdAsc(chatId);
        if (!stored.isEmpty()) {
            return stored.stream()
                    .map(m -> new AgentMessageDTO(m.getRole(), fromJson(m.getContent())))
                    .toList();
        }
        return legacyFromMemory(chatId);
    }

    /** Chats created before agent_message existed: text-only, from model memory. */
    private List<AgentMessageDTO> legacyFromMemory(UUID chatId) {
        return chatMemory.get(chatId.toString()).stream()
                .filter(m -> VISIBLE_MEMORY_ROLES.contains(m.getMessageType().name()))
                .map(m -> new AgentMessageDTO(
                        m.getMessageType() == MessageType.USER ? USER : ASSISTANT,
                        List.of(AgentSegment.text(m.getText()))))
                .toList();
    }

    private String toJson(List<AgentSegment> segments) {
        try {
            return objectMapper.writeValueAsString(segments);
        } catch (Exception e) {
            log.error("Failed to serialize agent segments for chat", e);
            return "[]";
        }
    }

    private List<AgentSegment> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<AgentSegment>>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize agent segments", e);
            return List.of();
        }
    }
}
