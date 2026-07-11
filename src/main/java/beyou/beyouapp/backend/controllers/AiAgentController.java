package beyou.beyouapp.backend.controllers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.aiAgent.AiAgentService;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatMessageDTO;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
        return Map.of("reply", agentService.processMessage(chatId, request.userInput(), userId));
    }

    @GetMapping("/chats/{chatId}/messages")
    public List<ChatMessageDTO> getMessages(@PathVariable UUID chatId) {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        return agentService.getMessages(chatId, userId);
    }

    @DeleteMapping("/chats/{chatId}")
    public ResponseEntity<Map<String, String>> deleteChat(@PathVariable UUID chatId) {
        UUID userId = authenticatedUser.getAuthenticatedUser().getId();
        chatService.deleteChat(chatId, userId);
        return ResponseEntity.ok(Map.of("success", "Chat deleted successfully"));
    }

    public record AiAgentRequest(@NotBlank @Size(max = 4000) String userInput) {
    }

    public record CreateChatRequest(@Size(max = 255) String title) {
    }
}
