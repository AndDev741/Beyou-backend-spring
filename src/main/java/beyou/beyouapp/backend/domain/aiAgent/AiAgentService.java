package beyou.beyouapp.backend.domain.aiAgent;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
import beyou.beyouapp.backend.domain.aiAgent.chat.AgentMessageService;
import beyou.beyouapp.backend.domain.aiAgent.chat.Chat;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentMessageDTO;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentSegment;
import beyou.beyouapp.backend.domain.aiAgent.dto.AgentEvent;
import beyou.beyouapp.backend.user.User;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

@Service
@Slf4j
public class AiAgentService {

    /** SSE comment sent on this cadence keeps idle connections alive through
     *  proxies while the agent thinks or runs a slow tool. */
    private static final long HEARTBEAT_SECONDS = 15;

    private final ChatClient chatClient;
    private final ChatService chatService;
    private final AgentMessageService agentMessageService;
    private final Object[] toolCallbacks;
    private final Resource systemTemplate;

    // ponytail: one daemon thread — pings are trivial writes; bump the pool
    // only if heartbeat latency across many concurrent streams ever shows up.
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "agent-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public AiAgentService(DeepSeekChatModel chatModel,
            ChatMemory chatMemory,
            ChatService chatService,
            AgentMessageService agentMessageService,
            Tools tools,
            MeterRegistry meterRegistry,
            @Value("classpath:/prompts/aiAgent.st") Resource systemTemplate) {
        this.chatService = chatService;
        this.agentMessageService = agentMessageService;
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

        // Non-streaming path (mobile): no ordered tool capture, so the transcript
        // is text-only. Interleaved tool segments arrive once mobile streams too.
        agentMessageService.recordTurn(chatId, userInput, List.of(AgentSegment.text(reply)));
        // Reload-by-id: tools may have re-saved the chat during the call above.
        chatService.touch(chatId, userId);
        return reply;
    }

    public SseEmitter streamMessage(UUID chatId, String userInput, UUID userId, String currentPage) {
        Chat chat = chatService.getChat(chatId, userId);

        SseEmitter emitter = new SseEmitter(180_000L);

        // The heartbeat thread and the reactor thread both write to this one
        // emitter — serialize every write so events never interleave mid-frame.
        Object streamLock = new Object();

        // Every event flows through here in causal order (tokens from the Flux,
        // tools from MeteredToolCallback). The builder observes FIRST, so a
        // dead client (send throwing) never costs us the persisted transcript.
        AgentTurnBuilder turn = new AgentTurnBuilder();
        Consumer<AgentEvent> send = event -> {
            turn.observe(event);
            synchronized (streamLock) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event.type())
                            .data(event.data(), MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        Flux<String> tokens = buildPrompt(chat, userInput, currentPage, Map.of("userId", userId, "chatId", chatId, MeteredToolCallback.EVENTS_KEY, send))
                .stream()
                .content();

        Disposable subscription = tokens.subscribe(
                token -> send.accept(AgentEvent.token(token)),
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
                    List<AgentSegment> segments = turn.build();
                    try {
                        agentMessageService.recordTurn(chatId, userInput, segments);
                        send.accept(AgentEvent.done(segments));
                    } catch (RuntimeException e) {
                        log.error("Error trying to emit complete... {}", e.getClass().getSimpleName(), e);
                    } finally {
                        emitter.complete();
                        // Blocking JPA call on the netty event loop — fine at
                        // our volume, move to publishOn(Schedulers.boundedElastic()) if
                        // stream latency ever spikes.
                        chatService.touch(chatId, userId);
                    }
                });

        // Heartbeat: also the ONLY dead-client detector during quiet pauses
        // (no token send to fail on) — its failure aborts the stream.
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            synchronized (streamLock) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    emitter.complete(); // -> onCompletion cancels heartbeat + disposes
                }
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Runnable cleanup = () -> {
            heartbeat.cancel(false);
            subscription.dispose();
        };
        emitter.onTimeout(cleanup);
        emitter.onCompletion(cleanup);

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

    /** Full display transcript, oldest first (ownership checked here). */
    public List<AgentMessageDTO> getMessages(UUID chatId, UUID userId) {
        chatService.getChat(chatId, userId);
        return agentMessageService.getMessages(chatId);
    }

    private String orNone(String value) {
        return value == null || value.isBlank() ? "(none yet)" : value;
    }
}
