package beyou.beyouapp.backend.domain.aiAgent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatMessageDTO;

@Service
public class AiAgentService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatService chatService;
    private final Tools tools;

    public AiAgentService(ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            ChatService chatService,
            Tools tools) {
        this.chatMemory = chatMemory;
        this.chatService = chatService;
        this.tools = tools;
        this.chatClient = chatClientBuilder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String processMessage(UUID chatId, String userInput, UUID userId) {
        Chat chat = chatService.getChat(chatId, userId);

        String reply = this.chatClient.prompt()
                .user(userInput)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chat.getId().toString()))
                .tools(tools)
                .toolContext(Map.of("userId", userId))
                .call()
                .content();

        chatService.touch(chat);
        return reply;
    }

    /** Window-limited history (the model's working memory), oldest first. */
    public List<ChatMessageDTO> getMessages(UUID chatId, UUID userId) {
        Chat chat = chatService.getChat(chatId, userId);
        return chatMemory.get(chat.getId().toString()).stream()
                .map(message -> new ChatMessageDTO(message.getMessageType().name(), message.getText()))
                .toList();
    }
}
