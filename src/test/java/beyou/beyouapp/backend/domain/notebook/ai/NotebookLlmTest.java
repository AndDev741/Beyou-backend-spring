package beyou.beyouapp.backend.domain.notebook.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ByteArrayResource;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;

/**
 * The notebook AI's time budget. The production numbers are 90 and 20 seconds; these tests use
 * the same rules scaled down to milliseconds, through the package-private constructor.
 *
 * <p>The budget exists because the request dies at 100 seconds whatever the server does
 * (Cloudflare, and the web client's own timeout), so a call that runs longer is an error the
 * person sees for work that may still be finished and saved afterwards.
 */
class NotebookLlmTest {

    record Answer(String text) {}

    private static final Duration BUDGET = Duration.ofMillis(600);
    private static final Duration MIN_RETRY = Duration.ofMillis(300);

    private ChatModel chatModel;
    private NotebookLlm llm;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        llm = new NotebookLlm(chatModel,
                new ByteArrayResource("Tutor. Language {language}. Today {today}.".getBytes()), BUDGET, MIN_RETRY);
    }

    @AfterEach
    void tearDown() {
        llm.shutdown();
        MDC.clear();
    }

    @Test
    void aModelThatNeverAnswersEndsInAiUnavailableWithinTheBudget() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return ok("{\"text\":\"too late\"}");
        });

        long started = System.nanoTime();
        assertThatThrownBy(() -> llm.call(Answer.class, "Draft a roadmap", user()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.AI_UNAVAILABLE));
        long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(tookMillis).isLessThan(BUDGET.toMillis() + 400);
        // The first attempt used the whole budget, so there was nothing left to retry with.
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    /** The case from local testing: the provider failed, and the retry answered. */
    @Test
    void aFailureWithTimeLeftIsRetriedOnce() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("Error reading response"))
                .thenReturn(ok("{\"text\":\"a roadmap\"}"));

        Answer answer = llm.call(Answer.class, "Draft a roadmap", user());

        assertThat(answer.text()).isEqualTo("a roadmap");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    /** A retry that would be cut off after a moment is a slower error, so it never starts. */
    @Test
    void aFailureNearTheDeadlineIsNotRetried() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(BUDGET.minus(MIN_RETRY).toMillis() + 100);
            throw new RuntimeException("Error reading response");
        });

        assertThatThrownBy(() -> llm.call(Answer.class, "Draft a roadmap", user()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.AI_UNAVAILABLE));
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    /** Attempts run on another thread; the user id in their log lines must come along. */
    @Test
    void theAttemptKeepsTheRequestsLogContext() {
        AtomicReference<String> seen = new AtomicReference<>();
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            seen.set(MDC.get("userId"));
            return ok("{\"text\":\"ok\"}");
        });
        MDC.put("userId", "user-123");

        llm.call(Answer.class, "Draft a roadmap", user());

        assertThat(seen.get()).isEqualTo("user-123");
    }

    private static ChatResponse ok(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    private static User user() {
        User u = new User();
        u.setLanguageInUse("en");
        return u;
    }
}
