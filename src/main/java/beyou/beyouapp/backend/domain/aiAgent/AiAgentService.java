package beyou.beyouapp.backend.domain.aiAgent;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import beyou.beyouapp.backend.domain.aiAgent.AiIconCatalog;
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

    /** Concurrent live streams per user (two tabs = 2). Caps LLM cost/resource abuse. */
    private static final int MAX_CONCURRENT_STREAMS_PER_USER = 2;

    private final ChatClient chatClient;
    private final ChatService chatService;
    private final AgentMessageService agentMessageService;
    private final Object[] toolCallbacks;
    private final Resource systemTemplate;

    // emitter.send() is BLOCKING: a stalled client applies TCP backpressure and
    // holds its scheduler thread until the emitter times out. A pool bounds the
    // blast radius (one stalled client can't starve every other stream's ping);
    // combined with the per-user stream cap, total concurrent streams are bounded.
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newScheduledThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()), runnable -> {
                Thread thread = new Thread(runnable, "agent-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    // Per-user count of in-flight streams. Entries are tiny and bounded by the
    // active-user set, so they're left in place rather than pruned on zero.
    private final ConcurrentMap<UUID, AtomicInteger> activeStreams = new ConcurrentHashMap<>();

    public AiAgentService(ChatModel chatModel,
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

        // Per-user concurrency cap: reject a new stream cleanly (one parseable
        // error event) instead of opening an unbounded number of LLM calls.
        AtomicInteger userStreams = activeStreams.computeIfAbsent(userId, k -> new AtomicInteger());
        if (userStreams.incrementAndGet() > MAX_CONCURRENT_STREAMS_PER_USER) {
            userStreams.decrementAndGet();
            SseEmitter rejected = new SseEmitter(5_000L);
            try {
                AgentEvent event = AgentEvent.error("TOO_MANY_STREAMS");
                rejected.send(SseEmitter.event().name(event.type()).data(event.data(), MediaType.APPLICATION_JSON));
            } catch (IOException ignored) {
                // client already gone — nothing to deliver
            }
            rejected.complete();
            return rejected;
        }

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
                    // Tool side-effects already committed; persist the partial turn
                    // (if anything streamed) so history isn't lost, THEN report error.
                    persistTurnSafely(chatId, userInput, turn.build());
                    try {
                        send.accept(AgentEvent.error(error.getClass().getSimpleName()));
                    } catch (RuntimeException e) {
                        log.error("Error trying to emit error... {}", e.getClass().getSimpleName(), e);
                    } finally {
                        emitter.complete();
                        chatService.touch(chatId, userId);
                    }
                },
                () -> {
                    List<AgentSegment> segments = turn.build();
                    // Persist FIRST: only emit done if the transcript is safe.
                    // If persistence fails, the client must learn it failed rather
                    // than get a clean close with committed tool side-effects but
                    // no chat record — so emit an error event instead of done.
                    boolean persisted = persistTurnSafely(chatId, userInput, segments);
                    try {
                        send.accept(persisted
                                ? AgentEvent.done(segments)
                                : AgentEvent.error("TRANSCRIPT_PERSIST_FAILED"));
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

        // onCompletion fires exactly once for any terminal state (complete,
        // error, timeout), so the stream slot is always released.
        Runnable cleanup = () -> {
            heartbeat.cancel(false);
            subscription.dispose();
            userStreams.decrementAndGet();
        };
        emitter.onTimeout(cleanup);
        emitter.onCompletion(cleanup);

        return emitter;
    }

    /** Persist a turn without letting a DB failure escape; returns whether it stuck.
     *  Empty segments (nothing streamed before an early error) are skipped. */
    private boolean persistTurnSafely(UUID chatId, String userInput, List<AgentSegment> segments) {
        if (segments.isEmpty()) return false;
        try {
            agentMessageService.recordTurn(chatId, userInput, segments);
            return true;
        } catch (RuntimeException e) {
            log.error("Failed to persist agent transcript for chat {}", chatId, e);
            return false;
        }
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
