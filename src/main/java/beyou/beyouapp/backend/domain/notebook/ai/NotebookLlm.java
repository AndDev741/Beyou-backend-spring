package beyou.beyouapp.backend.domain.notebook.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Every model call the notebook makes goes through here: the fallback chain, one structured
 * answer, one retry asking for valid JSON, then {@link ErrorKey#AI_UNAVAILABLE}.
 *
 * <p>The same contract as the onboarding suggestions, on purpose: no tools, no streaming, no
 * memory. A notebook call is a question with its context attached, and the context (passages,
 * the chat so far) is assembled by the caller and sent in full every time.
 *
 * <p>The whole call, retry included, has {@link #BUDGET}. Without one, a provider that hangs
 * can hold a request for minutes: up to two minutes of silence per attempt, per provider in the
 * chain, twice. Long before that the request is dead anyway. Cloudflare drops a request to
 * the API after 100 seconds, and the web client gives up at 100 too (`AI_TIMEOUT` in
 * `@beyou/api` notebook studyApi). Past that point the person sees an error for work the
 * server may still finish, and for the calls that save something (studio outputs, AI
 * flashcards) a second try would save it twice. So the server stops first and says so with
 * AI_UNAVAILABLE. The retry only starts when at least {@link #MIN_RETRY} of the budget is
 * left, since a retry cut off after five seconds would just be a slower error.
 *
 * <p>Each attempt runs on a virtual thread so the request can stop waiting at the deadline.
 * The abandoned HTTP call ends on its own read timeout; nothing reads its answer.
 */
@Component
@Slf4j
public class NotebookLlm {

    /** Keep below the 100 seconds Cloudflare and the web client allow. See the class comment. */
    static final Duration BUDGET = Duration.ofSeconds(90);
    static final Duration MIN_RETRY = Duration.ofSeconds(20);

    private final ChatClient chatClient;
    private final Resource systemTemplate;
    private final Duration budget;
    private final Duration minRetry;
    private final ExecutorService attempts = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public NotebookLlm(ChatModel chatModel,
            @Value("classpath:/prompts/notebookTutor.st") Resource systemTemplate) {
        this(chatModel, systemTemplate, BUDGET, MIN_RETRY);
    }

    /** For tests, which cannot wait 90 seconds to watch a deadline pass. */
    NotebookLlm(ChatModel chatModel, Resource systemTemplate, Duration budget, Duration minRetry) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.systemTemplate = systemTemplate;
        this.budget = budget;
        this.minRetry = minRetry;
    }

    public <T> T call(Class<T> type, String userMessage, User user) {
        Instant deadline = Instant.now().plus(budget);
        try {
            return within(deadline, () -> doCall(type, userMessage, user));
        } catch (RuntimeException first) {
            Duration left = Duration.between(Instant.now(), deadline);
            if (left.compareTo(minRetry) < 0) {
                log.error("Notebook AI call failed with {} ms of the budget left, not retrying: {}",
                        left.toMillis(), first.getMessage());
                throw unavailable();
            }
            log.warn("Notebook AI call failed, retrying once: {}", first.getMessage());
            try {
                return within(deadline,
                        () -> doCall(type, userMessage + "\nIMPORTANT: return ONLY valid JSON matching the schema.", user));
            } catch (RuntimeException second) {
                log.error("Notebook AI retry failed", second);
                throw unavailable();
            }
        }
    }

    private <T> T within(Instant deadline, Callable<T> attempt) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();
        Future<T> future = attempts.submit(() -> {
            // The user id in every log line comes from the MDC, which is per thread.
            if (logContext != null) MDC.setContextMap(logContext);
            try {
                return attempt.call();
            } finally {
                MDC.clear();
            }
        });
        try {
            long millis = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("No answer within the notebook AI budget");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the notebook AI");
        }
    }

    private <T> T doCall(Class<T> type, String userMessage, User user) {
        T result = chatClient.prompt()
                .system(s -> s.text(systemTemplate)
                        .param("language", user.getLanguageInUse() != null ? user.getLanguageInUse() : "en")
                        .param("today", UserDateResolver.today(user).toString()))
                .user(userMessage)
                .call()
                .entity(type);
        if (result == null) {
            throw new IllegalStateException("The model returned nothing");
        }
        return result;
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorKey.AI_UNAVAILABLE, "The study AI is unavailable");
    }

    @PreDestroy
    void shutdown() {
        attempts.shutdownNow();
    }
}
