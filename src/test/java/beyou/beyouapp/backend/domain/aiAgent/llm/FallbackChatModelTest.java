package beyou.beyouapp.backend.domain.aiAgent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;

class FallbackChatModelTest {

    private static final Prompt PROMPT = new Prompt("hi");
    private static final ChatResponse OK =
            new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));

    private final ChatModel first = mock(ChatModel.class);
    private final ChatModel second = mock(ChatModel.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock();

    private FallbackChatModel twoLinkChain() {
        return new FallbackChatModel(
                List.of(new NamedChatModel("first", first), new NamedChatModel("second", second)),
                Duration.ofSeconds(300), Duration.ofSeconds(30), meters, clock);
    }

    @Test
    void call_firstProviderHealthy_secondNeverTouched() {
        when(first.call(PROMPT)).thenReturn(OK);

        ChatResponse response = twoLinkChain().call(PROMPT);

        assertThat(response).isSameAs(OK);
        verifyNoInteractions(second);
        assertThat(count("beyou.ai.llm.calls", "provider", "first", "outcome", "ok")).isEqualTo(1.0);
    }

    @Test
    void call_firstFails_fallsBackToSecond() {
        when(first.call(PROMPT)).thenThrow(new RuntimeException("boom"));
        when(second.call(PROMPT)).thenReturn(OK);

        ChatResponse response = twoLinkChain().call(PROMPT);

        assertThat(response).isSameAs(OK);
        assertThat(count("beyou.ai.llm.calls", "provider", "first", "outcome", "error")).isEqualTo(1.0);
        assertThat(count("beyou.ai.llm.fallback", "from", "first", "reason", "error")).isEqualTo(1.0);
    }

    @Test
    void call_allFail_lastExceptionPropagates_exhaustedCounted() {
        when(first.call(PROMPT)).thenThrow(new RuntimeException("one"));
        when(second.call(PROMPT)).thenThrow(new RuntimeException("two"));

        assertThatThrownBy(() -> twoLinkChain().call(PROMPT)).hasMessage("two");
        assertThat(meters.counter("beyou.ai.llm.exhausted").count()).isEqualTo(1.0);
    }

    @Test
    void call_rateLimitedProvider_skippedUntilCooldownExpires() {
        FallbackChatModel chain = twoLinkChain();
        when(first.call(PROMPT)).thenThrow(new RuntimeException("HTTP 429 Too Many Requests"));
        when(second.call(PROMPT)).thenReturn(OK);

        chain.call(PROMPT); // first fails with 429 -> 300s cooldown
        chain.call(PROMPT); // first must be skipped, served by second
        verify(first, times(1)).call(PROMPT);
        assertThat(count("beyou.ai.llm.calls", "provider", "first", "outcome", "skipped_cooldown"))
                .isEqualTo(1.0);
        assertThat(count("beyou.ai.llm.fallback", "from", "first", "reason", "rate_limit"))
                .isEqualTo(1.0);

        clock.advanceSeconds(301);
        reset(first);
        when(first.call(PROMPT)).thenReturn(OK);
        chain.call(PROMPT); // cooldown expired -> first serves again
        verify(first, times(1)).call(PROMPT);
    }

    @Test
    void call_quotaErrors402And413_getLongCooldown() {
        // Cerebras free tier answers 402 (payment wall), Groq free tier answers 413
        // (prompt bigger than TPM limit) — neither heals in 30s, so they must take
        // the long (rate-limit) cooldown, not the short error one.
        FallbackChatModel chain = twoLinkChain();
        when(first.call(PROMPT)).thenThrow(new RuntimeException(
                "com.openai.errors.UnexpectedStatusCodeException: 402: payment_required"));
        when(second.call(PROMPT)).thenReturn(OK);

        chain.call(PROMPT);          // first fails with 402 -> long cooldown
        clock.advanceSeconds(60);    // past the short cooldown, inside the long one
        chain.call(PROMPT);
        verify(first, times(1)).call(PROMPT); // still skipped
        assertThat(count("beyou.ai.llm.fallback", "from", "first", "reason", "rate_limit"))
                .isEqualTo(1.0);
    }

    @Test
    void call_lastProviderAlwaysAttempted_evenInCooldown() {
        FallbackChatModel single = new FallbackChatModel(
                List.of(new NamedChatModel("only", first)),
                Duration.ofSeconds(300), Duration.ofSeconds(30), meters, clock);
        when(first.call(PROMPT)).thenThrow(new RuntimeException("HTTP 429"));

        assertThatThrownBy(() -> single.call(PROMPT)).hasMessageContaining("429");
        // still in cooldown, but it's the last link -> attempted anyway
        assertThatThrownBy(() -> single.call(PROMPT)).hasMessageContaining("429");
        verify(first, times(2)).call(PROMPT);
    }

    @Test
    void stream_failsBeforeFirstChunk_fallsBackTransparently() {
        when(first.stream(PROMPT)).thenReturn(Flux.error(new RuntimeException("boom")));
        when(second.stream(PROMPT)).thenReturn(Flux.just(OK));

        List<ChatResponse> out = twoLinkChain().stream(PROMPT).collectList().block();

        assertThat(out).containsExactly(OK);
        assertThat(count("beyou.ai.llm.calls", "provider", "second", "outcome", "ok")).isEqualTo(1.0);
    }

    @Test
    void stream_throwsSynchronously_fallsBackTransparently() {
        // some SDKs throw on subscribe instead of returning an error Flux
        when(first.stream(PROMPT)).thenThrow(new RuntimeException("connect refused"));
        when(second.stream(PROMPT)).thenReturn(Flux.just(OK));

        List<ChatResponse> out = twoLinkChain().stream(PROMPT).collectList().block();

        assertThat(out).containsExactly(OK);
    }

    @Test
    void stream_failsMidStream_propagatesWithoutRetry() {
        when(first.stream(PROMPT)).thenReturn(
                Flux.concat(Flux.just(OK), Flux.error(new RuntimeException("mid-stream"))));

        assertThatThrownBy(() -> twoLinkChain().stream(PROMPT).collectList().block())
                .hasMessageContaining("mid-stream");
        verify(second, never()).stream(any(Prompt.class));
    }

    @Test
    void stream_allFailBeforeEmission_exhaustedCounted() {
        when(first.stream(PROMPT)).thenReturn(Flux.error(new RuntimeException("one")));
        when(second.stream(PROMPT)).thenReturn(Flux.error(new RuntimeException("two")));

        assertThatThrownBy(() -> twoLinkChain().stream(PROMPT).collectList().block())
                .hasMessageContaining("two");
        assertThat(meters.counter("beyou.ai.llm.exhausted").count()).isEqualTo(1.0);
    }

    @Test
    void providerNames_reflectChainOrder() {
        assertThat(twoLinkChain().providerNames()).containsExactly("first", "second");
    }

    @Test
    void getOptions_isToolCallingChatOptions_soChatClientAttachesTools() {
        // ChatClient calls getOptions() on the injected model and only attaches tool
        // callbacks (and the ToolCallingAdvisor only engages) when the result is a
        // ToolCallingChatOptions.
        assertThat(twoLinkChain().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
    }

    @Test
    void call_convertsPromptOptionsToDelegateNativeType_keepingTools() {
        // The ChatClient puts portable DefaultToolCallingChatOptions in the prompt;
        // each provider model hard-casts prompt options to ITS native type, so the
        // chain must retype them per delegate (keeping the delegate's model id).
        when(first.getOptions()).thenReturn(
                OpenAiChatOptions.builder().model("llama-3.3-70b").build());
        when(first.call(any(Prompt.class))).thenReturn(OK);

        ToolCallback tool = mock(ToolCallback.class);
        Prompt prompt = new Prompt(List.of(new UserMessage("hi")),
                ToolCallingChatOptions.builder()
                        .toolCallbacks(List.of(tool))
                        .toolContext(Map.of("userId", "u1"))
                        .build());

        twoLinkChain().call(prompt);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(first).call(sent.capture());
        assertThat(sent.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) sent.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("llama-3.3-70b");
        assertThat(options.getToolCallbacks()).containsExactly(tool);
        assertThat(options.getToolContext()).containsEntry("userId", "u1");
    }

    @Test
    void stream_convertsPromptOptionsPerDelegate_onFallback() {
        when(first.getOptions()).thenReturn(OpenAiChatOptions.builder().model("m-first").build());
        when(second.getOptions()).thenReturn(OpenAiChatOptions.builder().model("m-second").build());
        when(first.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("boom")));
        when(second.stream(any(Prompt.class))).thenReturn(Flux.just(OK));

        Prompt prompt = new Prompt(List.of(new UserMessage("hi")),
                ToolCallingChatOptions.builder().build());
        twoLinkChain().stream(prompt).collectList().block();

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(second).stream(sent.capture());
        assertThat(sent.getValue().getOptions().getModel()).isEqualTo("m-second");
    }

    @Test
    void call_promptWithoutOptions_passesThroughUnchanged() {
        when(first.call(PROMPT)).thenReturn(OK);

        assertThat(twoLinkChain().call(PROMPT)).isSameAs(OK);
    }

    private double count(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** Fixed clock the test can move forward — cooldown expiry without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-17T10:00:00Z");

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
