package beyou.beyouapp.backend.domain.aiAgent.llm;

import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;

import beyou.beyouapp.backend.domain.aiAgent.AiAgentService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Builds the app's only {@link ChatModel}: an OpenAI-compatible client for the endpoint in
 * {@link LlmProperties}. It is built by hand from the spring-ai-openai library because the
 * streaming path needs four distinct timeouts, and the starter's own chat autoconfiguration
 * (switched off with {@code spring.ai.model.chat: none}) takes a single one.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
@Slf4j
public class LlmConfig {

    /** TCP connect only. Short, so an unreachable endpoint fails fast. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Gap between two reads. During a stream that is the silence between chunks, however
     * long the whole answer runs. This is the stall detector.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    /** Sending the request body (system prompt + tool schemas + history). */
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Ceiling on the WHOLE call. For a streaming request that includes reading every chunk,
     * so it caps how long an answer may take to finish. OkHttp cancels the HTTP/2 stream
     * when it expires, which surfaces as {@code StreamResetException: stream was reset:
     * CANCEL} wrapped in {@code OpenAIIoException: Stream failed} in the middle of an answer.
     *
     * <p>Derived from the SSE budget because the two are ordered on purpose and drifted
     * apart once already: this used to be a flat 120s labelled a read timeout, so raising
     * the emitter to 300s changed nothing and long answers kept dying at two minutes.
     * Staying just under the emitter budget means the upstream call is always the first
     * thing to give up, leaving room to send the error event.
     */
    static final Duration CALL_TIMEOUT = AiAgentService.STREAM_TIMEOUT.minusSeconds(10);

    @Bean
    ChatModel chatModel(LlmProperties props, ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        if (!props.isConfigured()) {
            // Booting anyway keeps the rest of the API up when the endpoint is missing,
            // and the stand-in makes no network call.
            log.warn("No LLM endpoint configured (LLM_BASE_URL, LLM_API_KEY, LLM_MODEL): "
                    + "every AI feature will answer as unavailable");
            return new UnconfiguredChatModel();
        }
        log.info("LLM endpoint: {} (model {})", props.baseUrl(), props.model());
        // One options object, one HTTP client, both facades: call() uses sync, stream()
        // uses async, and async() hands back the same wiring.
        ClientOptions options = clientOptions(props, observationRegistry, meterRegistry);
        OpenAIClientImpl client = new OpenAIClientImpl(options);
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder().model(props.model()).build())
                // A manual build gets no observability for free. Without these two the
                // model vanishes from the gen_ai_* Grafana panels.
                .observationRegistry(observationRegistry)
                .meterRegistry(meterRegistry)
                .build();
    }

    private static ClientOptions clientOptions(LlmProperties props,
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        Timeout timeout = agentTimeout();
        // Set on BOTH: the http client's values are the defaults, and the ones on
        // ClientOptions ride along as per-request options that SpringAiOpenAiHttpClient
        // re-applies to every call, overwriting all four. Same object, so they agree.
        return ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder()
                        .observationRegistry(observationRegistry)
                        .meterRegistry(meterRegistry)
                        .timeout(timeout)
                        .build())
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .timeout(timeout)
                // The gateway already retries across its providers. A second layer of
                // retries here would only lengthen the wait before the user sees an error.
                .maxRetries(0)
                .putHeader("User-Agent", "spring-ai-openai")
                .build();
    }

    /**
     * Every field set explicitly: an unset one falls back to {@code request}, which is how
     * a lone {@code Duration} ends up being the read timeout AND the whole-call cap.
     */
    static Timeout agentTimeout() {
        return Timeout.builder()
                .connect(CONNECT_TIMEOUT)
                .read(READ_TIMEOUT)
                .write(WRITE_TIMEOUT)
                .request(CALL_TIMEOUT)
                .build();
    }

    /**
     * Stands in when no endpoint is set. Every call fails here, in process, and the callers
     * already turn a failed call into their own "unavailable" answer.
     */
    static final class UnconfiguredChatModel implements ChatModel {

        private static final String MESSAGE =
                "No LLM endpoint configured: set LLM_BASE_URL, LLM_API_KEY and LLM_MODEL";

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new IllegalStateException(MESSAGE);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.error(new IllegalStateException(MESSAGE));
        }

        /** The ChatClient seeds every prompt from this, so it has to be a real object. */
        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
