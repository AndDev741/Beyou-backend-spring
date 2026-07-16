package beyou.beyouapp.backend.controllers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import beyou.beyouapp.backend.domain.aiAgent.AiAgentService;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentMessageDTO;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatResponseDTO;
import beyou.beyouapp.backend.domain.aiAgent.dto.CreateChatRequest;
import beyou.beyouapp.backend.domain.aiAgent.dto.RenameChatRequest;
import beyou.beyouapp.backend.domain.aiAgent.dto.AiAgentRequest;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/ai/agent")
@RequiredArgsConstructor
@Slf4j
public class AiAgentController {
    private final AiAgentService agentService;
    private final ChatService chatService;
    private final AuthenticatedUser authenticatedUser;

    @GetMapping("/chats")
    public List<ChatResponseDTO> getChats() {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        return chatService.getAllChats(userId);
    }

    @PostMapping("/chats")
    public ChatResponseDTO createChat(@RequestBody(required = false) CreateChatRequest request) {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        return chatService.createChat(request == null ? null : request.title(), userId);
    }

    @PostMapping("/chats/{chatId}")
    public Map<String, String> processMessage(@PathVariable UUID chatId, @RequestBody @Valid AiAgentRequest request) {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        log.info("Receiving agent message on chat {} for user {}", chatId, userId);
        return Map.of("reply",
                agentService.processMessage(chatId, request.userInput(), userId, request.currentPage()));
    }

    @PostMapping(value = "/chats/{chatId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@PathVariable UUID chatId, @RequestBody @Valid AiAgentRequest request,
            HttpServletResponse response) {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        log.info("Receiving agent message on chat {} for user {}", chatId, userId);
        // Tell nginx (and any proxy honoring it) not to buffer this response,
        // so tokens flush to the client immediately instead of in one blob.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return agentService.streamMessage(chatId, request.userInput(), userId, request.currentPage());
    }

    @GetMapping("/chats/{chatId}/messages")
    public List<AgentMessageDTO> getMessages(@PathVariable UUID chatId) {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        return agentService.getMessages(chatId, userId);
    }

    @PutMapping("/chats/{chatId}")
    public ChatResponseDTO renameChat(@PathVariable UUID chatId, @RequestBody @Valid RenameChatRequest request) {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        return chatService.renameChat(chatId, userId, request.title());
    }

    @DeleteMapping("/chats/{chatId}")
    public ResponseEntity<Map<String, String>> deleteChat(@PathVariable UUID chatId) {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        chatService.deleteChat(chatId, userId);
        return ResponseEntity.ok(Map.of("success", "Chat deleted successfully"));
    }

    /** Reset the agent: delete all chats and clear its remembered context. */
    @DeleteMapping("/chats")
    public ResponseEntity<Map<String, String>> deleteAllChats() {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        chatService.deleteAllChats(userId);
        return ResponseEntity.ok(Map.of("success", "All chats deleted successfully"));
    }

}
