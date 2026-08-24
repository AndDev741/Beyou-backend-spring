package beyou.beyouapp.backend.domain.aiAgent.llm;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;

import beyou.beyouapp.backend.domain.aiAgent.AiAgentService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Assembles the agent's LLM fallback chain. Free providers are manual
 * OpenAiChatModel instances built from the spring-ai-openai library (not
 * Spring beans); DeepSeek is the only auto-configured chat model
 * (spring.ai.model.chat: deepseek). Providers without an API key are
 * skipped, so dev/e2e boot with a DeepSeek-only chain and zero new env vars.
 */
@Configuration
@EnableConfigurationProperties(LlmChainProperties.class)
@Slf4j
public class LlmChainConfig {

    /** TCP connect only. Short: an unreachable endpoint should reach the next chain link fast. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Gap between two reads, so during a stream it is the silence between chunks, not the
     * length of the answer. This is the stall detector: it fires while nothing has been
     * emitted yet, which is the window where {@link FallbackChatModel} can still move to
     * the next provider.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    /** Sending the request body (system prompt + tool schemas + history). */
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Ceiling on the WHOLE call — for a streaming request that includes reading every
     * chunk, so it caps how long an answer may take to finish, not how long it may stall.
     * OkHttp cancels the HTTP/2 stream when it expires, which surfaces as
     * {@code StreamResetException: stream was reset: CANCEL} wrapped in
     * {@code OpenAIIoException: Stream failed} mid-answer — no fallback, because tokens
     * already reached the client.
     *
     * <p>Derived from the SSE budget rather than written out, because the two are ordered
     * on purpose and drifted apart once already: this used to be a flat 120s labelled a
     * read timeout, so raising the emitter to 300s changed nothing and long answers kept
     * dying at two minutes. Staying just under the emitter budget means the upstream call
     * is always the first thing to give up, leaving room to send the error event.
     */
    static final Duration CALL_TIMEOUT = AiAgentService.STREAM_TIMEOUT.minusSeconds(10);

    @Bean
    @Primary
    ChatModel agentChatModel(LlmChainProperties props, DeepSeekChatModel deepSeek,
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        List<NamedChatModel> chain = new ArrayList<>();
        for (String name : props.order()) {
            // Ahead of every other check: a blocked provider is not a misconfiguration
            // to work around, it is one this deployment is not allowed to call at all.
            if (props.isBlocked(name)) {
                log.warn("LLM chain: provider '{}' is blocked in this environment — dropping it from the chain",
                        name);
                continue;
            }
            if ("deepseek".equals(name)) {
                chain.add(new NamedChatModel("deepseek", deepSeek));
                continue;
            }
            LlmChainProperties.Provider provider = props.providers().get(name);
            if (provider == null || provider.apiKey() == null || provider.apiKey().isBlank()) {
                log.warn("LLM chain: provider '{}' not configured (missing entry or API key) — skipping", name);
                continue;
            }
            chain.add(new NamedChatModel(name,
                    openAiCompatible(provider, observationRegistry, meterRegistry)));
        }
        FallbackChatModel model = new FallbackChatModel(chain,
                Duration.ofSeconds(props.cooldownRateLimitSeconds()),
                Duration.ofSeconds(props.cooldownErrorSeconds()),
                meterRegistry, Clock.systemUTC());
        log.info("Agent LLM chain: {}", model.providerNames());
        return model;
    }

    private static ChatModel openAiCompatible(LlmChainProperties.Provider provider,
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        // One options object, one HTTP client, both facades: call() uses sync, stream()
        // uses async, and async() hands back the same wiring. Built by hand rather than
        // through OpenAiSetup because that helper takes a single Duration and spends it
        // on every field at once (see agentTimeout), and the streaming path needs the
        // per-chunk and whole-call limits to differ.
        ClientOptions options = clientOptions(provider, observationRegistry, meterRegistry);
        OpenAIClientImpl client = new OpenAIClientImpl(options);
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder().model(provider.model()).build())
                // manual builds don't get observability wired for free — without
                // these two, the provider vanishes from the gen_ai_* Grafana panels
                .observationRegistry(observationRegistry)
                .meterRegistry(meterRegistry)
                .build();
    }

    private static ClientOptions clientOptions(LlmChainProperties.Provider provider,
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
                .baseUrl(provider.baseUrl())
                .apiKey(provider.apiKey())
                .timeout(timeout)
                // maxRetries=0 -> fail fast, the chain IS the retry
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
}
