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

import org.springframework.ai.openai.setup.OpenAiSetup;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;

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

    @Bean
    @Primary
    ChatModel agentChatModel(LlmChainProperties props, DeepSeekChatModel deepSeek,
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        List<NamedChatModel> chain = new ArrayList<>();
        for (String name : props.order()) {
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
        // Spring AI's own client factory (the auto-config uses the same path);
        // maxRetries=0 -> fail fast, the chain IS the retry. Both sync and async
        // clients are needed: call() uses sync, stream() uses async.
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                provider.baseUrl(), provider.apiKey(),
                null, null, null, null,        // credential / azure / organization: unused
                false, false,                  // not Azure, not GitHub Models
                provider.model(),
                Duration.ofSeconds(120),       // generous read timeout for streaming
                0, null, null,                 // maxRetries, proxy, customHeaders
                observationRegistry, meterRegistry, List.of());
        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
                provider.baseUrl(), provider.apiKey(),
                null, null, null, null,
                false, false,
                provider.model(),
                Duration.ofSeconds(120),
                0, null, null,
                observationRegistry, meterRegistry, List.of());
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(asyncClient)
                .options(OpenAiChatOptions.builder().model(provider.model()).build())
                // manual builds don't get observability wired for free — without
                // these two, the provider vanishes from the gen_ai_* Grafana panels
                .observationRegistry(observationRegistry)
                .meterRegistry(meterRegistry)
                .build();
    }
}
