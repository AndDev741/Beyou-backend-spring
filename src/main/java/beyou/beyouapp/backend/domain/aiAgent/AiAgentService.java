package beyou.beyouapp.backend.domain.aiAgent;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.ai.AiIconCatalog;
import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatMessageDTO;
import beyou.beyouapp.backend.user.User;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class AiAgentService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatService chatService;
    private final List<ToolCallback> toolCallbacks;
    private final Resource systemTemplate;

    public AiAgentService(DeepSeekChatModel chatModel,
            ChatMemory chatMemory,
            ChatService chatService,
            Tools tools,
            MeterRegistry meterRegistry,
            @Value("classpath:/prompts/aiAgent.st") Resource systemTemplate) {
        this.chatMemory = chatMemory;
        this.chatService = chatService;
        // Metered wrappers feed the beyou.ai.tool timer on the Grafana AI dashboard.
        this.toolCallbacks = Arrays.stream(ToolCallbacks.from(tools))
                .map(callback -> (ToolCallback) new MeteredToolCallback(callback, meterRegistry))
                .toList();
        this.systemTemplate = systemTemplate;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String processMessage(UUID chatId, String userInput, UUID userId) {
        Chat chat = chatService.getChat(chatId, userId);
        User user = chat.getUser();

        String reply = this.chatClient.prompt()
                .system(s -> s.text(systemTemplate)
                        .param("language", user.getLanguageInUse() != null ? user.getLanguageInUse() : "en")
                        .param("iconCatalog", AiIconCatalog.promptCatalog())
                        .param("userContext", orNone(user.getUserContext()))
                        .param("userChatContext", orNone(chat.getUserContextInChat()))
                        .param("today", LocalDate.now().toString()))
                .user(userInput)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chat.getId().toString()))
                .toolCallbacks(toolCallbacks)
                .toolContext(Map.of("userId", userId, "chatId", chatId))
                .call()
                .content();

        // Reload-by-id: tools may have re-saved the chat during the call above.
        chatService.touch(chatId, userId);
        return reply;
    }

    /** Window-limited history (the model's working memory), oldest first. */
    public List<ChatMessageDTO> getMessages(UUID chatId, UUID userId) {
        Chat chat = chatService.getChat(chatId, userId);
        return chatMemory.get(chat.getId().toString()).stream()
                .map(message -> new ChatMessageDTO(message.getMessageType().name(), message.getText()))
                .toList();
    }

    private String orNone(String value) {
        return value == null || value.isBlank() ? "(none yet)" : value;
    }
}
