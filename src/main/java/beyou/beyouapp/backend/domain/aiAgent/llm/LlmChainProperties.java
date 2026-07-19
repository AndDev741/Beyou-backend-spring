package beyou.beyouapp.backend.domain.aiAgent.llm;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fallback chain config. {@code order} lists chain links first-to-last;
 * "deepseek" is the native starter bean, every other name must have an
 * entry in {@code providers} (OpenAI-compatible endpoint).
 */
@ConfigurationProperties(prefix = "ai.llm-chain")
public record LlmChainProperties(
        List<String> order,
        int cooldownRateLimitSeconds,
        int cooldownErrorSeconds,
        Map<String, Provider> providers) {

    public LlmChainProperties {
        order = order == null ? List.of("deepseek") : order;
        providers = providers == null ? Map.of() : providers;
    }

    public record Provider(String baseUrl, String apiKey, String model) {
    }
}
