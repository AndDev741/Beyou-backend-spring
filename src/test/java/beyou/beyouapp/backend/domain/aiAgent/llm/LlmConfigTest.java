package beyou.beyouapp.backend.domain.aiAgent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import beyou.beyouapp.backend.domain.aiAgent.AiAgentService;
import com.openai.core.Timeout;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

class LlmConfigTest {

    private static ChatModel build(LlmProperties props) {
        return new LlmConfig().chatModel(props, ObservationRegistry.NOOP, new SimpleMeterRegistry());
    }

    @Test
    void configuredEndpoint_buildsAnOpenAiModelCarryingTheConfiguredModelId() {
        ChatModel model = build(new LlmProperties("http://localhost:1/v1", "key-123", "some-combo"));

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
        // The ChatClient seeds every prompt from these options, so the model id has to be
        // here for any request to name a model at all.
        assertThat(((OpenAiChatOptions) model.getOptions()).getModel()).isEqualTo("some-combo");
    }

    /**
     * dev, e2e, CI and the test profile all boot with nothing set. The context has to come
     * up, and a call has to fail in process: the four AI features each turn a failed call
     * into their own "unavailable" answer.
     */
    @Test
    void unsetEndpoint_bootsWithAModelThatFailsEveryCall() {
        ChatModel model = build(new LlmProperties("", "", ""));

        assertThat(model).isInstanceOf(LlmConfig.UnconfiguredChatModel.class);
        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_BASE_URL");
        assertThatThrownBy(() -> model.stream(new Prompt("hi")).blockLast())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_BASE_URL");
        // The ChatClient calls getOptions().mutate() before it reaches the model. A null
        // here would turn the clear message above into a NullPointerException.
        assertThat(model.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
    }

    @Test
    void partlyFilledEndpoint_countsAsUnset() {
        assertThat(build(new LlmProperties("http://localhost:1/v1", "", "some-combo")))
                .isInstanceOf(LlmConfig.UnconfiguredChatModel.class);
        assertThat(build(new LlmProperties("http://localhost:1/v1", "key-123", null)))
                .isInstanceOf(LlmConfig.UnconfiguredChatModel.class);
        assertThat(build(new LlmProperties(" ", "key-123", "some-combo")))
                .isInstanceOf(LlmConfig.UnconfiguredChatModel.class);
    }

    /**
     * The four HTTP timeouts of the client, and why they are ordered the way they are. The
     * SDK derives an unset field from {@code request}, so handing it one Duration silently
     * makes the per-chunk limit and the whole-call ceiling the same number. That is how a
     * "generous read timeout for streaming" of 120s became a hard two-minute cap on every
     * answer, cancelling the HTTP/2 stream mid-sentence while the SSE emitter still had
     * minutes of budget left.
     */
    @Test
    void streamingTimeouts_stayOrderedAndDistinct() {
        Timeout timeout = LlmConfig.agentTimeout();

        // A stall has to be caught before the whole-call cap, or the cap is the only
        // thing that ever fires.
        assertThat(timeout.read()).isLessThan(timeout.request());
        assertThat(timeout.connect()).isLessThan(timeout.read());

        // Set on its own. Deriving it from request is the collapse that caused the bug.
        assertThat(timeout.read()).isEqualTo(Duration.ofSeconds(120));

        // The upstream call gives up first, leaving the turn room to report the failure
        // before the client-facing emitter times out with no error event.
        assertThat(timeout.request()).isLessThan(AiAgentService.STREAM_TIMEOUT);
    }
}
