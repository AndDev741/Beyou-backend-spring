package beyou.beyouapp.backend.domain.aiAgent;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import beyou.beyouapp.backend.domain.ai.AiIconCatalog;
import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.ChatMessageDTO;
import beyou.beyouapp.backend.domain.aiAgent.dto.AgentEvent;
import beyou.beyouapp.backend.user.User;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

@Service
@Slf4j
public class AiAgentService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatService chatService;
    private final Object[] toolCallbacks;
    private final Resource systemTemplate;

    public AiAgentService(DeepSeekChatModel chatModel,
            ChatMemory chatMemory,
            ChatService chatService,
            Tools tools,
            MeterRegistry meterRegistry,
            @Value("classpath:/prompts/aiAgent.st") Resource systemTemplate) {
        this.chatMemory = chatMemory;
        this.chatService = chatService;
        this.toolCallbacks = Arrays.stream(ToolCallbacks.from(tools))
                .map(callback -> (Object) new MeteredToolCallback(callback, meterRegistry))
                .toArray();
        this.systemTemplate = systemTemplate;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String processMessage(UUID chatId, String userInput, UUID userId, String currentPage) {
        Chat chat = chatService.getChat(chatId, userId);

        String reply = buildPrompt(chat, userInput, currentPage, Map.of("userId", userId, "chatId", chatId))
                .call()
                .content();

        // Reload-by-id: tools may have re-saved the chat during the call above.
        chatService.touch(chatId, userId);
        return reply;
    }

    public SseEmitter streamMessage(UUID chatId, String userInput, UUID userId, String currentPage) {
        Chat chat = chatService.getChat(chatId, userId);

        SseEmitter emitter = new SseEmitter(180_000L);

        Consumer<AgentEvent> send = event -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type())
                        .data(event.data(), MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        Flux<String> tokens = buildPrompt(chat, userInput, currentPage, Map.of("userId", userId, "chatId", chatId, MeteredToolCallback.EVENTS_KEY, send))
                .stream()
                .content();

        StringBuilder fullReply = new StringBuilder();
        Disposable subscription = tokens.subscribe(
                token -> {
                    fullReply.append(token);
                    send.accept(AgentEvent.token(token));
                },
                error -> {
                    log.error("Error on agent stream subscription -> {}", error.getMessage(), error);
                    try {
                        send.accept(AgentEvent.error(error.getClass().getSimpleName()));
                    } catch (RuntimeException e) {
                        log.error("Error trying to emit error... {}", e.getClass().getSimpleName(), e);
                    } finally {
                        emitter.complete();
                    }
                },
                () -> {
                    try{
                        String response = fullReply.toString();
                        send.accept(AgentEvent.done(response));
                    }catch(RuntimeException e){
                        log.error("Error trying to emit complete... {}", e.getClass().getSimpleName(), e);
                    }finally{
                        emitter.complete();
                        // Blocking call inside a event loop thread, hope will never cause a problem, move to publishOn(Schedulers.boundedElastic()) if stream latency ever spikes
                        chatService.touch(chatId, userId);
                    }
                });

        emitter.onTimeout(subscription::dispose);
        emitter.onCompletion(subscription::dispose);

        return emitter;

    }

    private ChatClient.ChatClientRequestSpec buildPrompt(Chat chat, String userInput, String currentPage, Map<String, Object> toolContext) {
        User user = chat.getUser();
        return this.chatClient.prompt()
            .system(s -> s.text(systemTemplate)
                .param("language", user.getLanguageInUse() != null ? user.getLanguageInUse() : "en")
                .param("iconCatalog", AiIconCatalog.promptCatalog())
                .param("userContext", orNone(user.getUserContext()))
                .param("userChatContext", orNone(chat.getUserContextInChat()))
                .param("currentPage", orNone(currentPage))
                .param("today", LocalDate.now().toString()))
            .user(userInput)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chat.getId().toString()))
            .tools(toolCallbacks)
            .toolContext(toolContext);
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
