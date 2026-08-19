package beyou.beyouapp.backend.domain.aiAgent.llm;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fallback chain config. {@code order} lists chain links first-to-last;
 * "deepseek" is the native starter bean, every other name must have an
 * entry in {@code providers} (OpenAI-compatible endpoint).
 *
 * <p>{@code blocked} names providers that must never join the chain in this
 * environment, whatever {@code order} says. It exists because {@code order} is
 * an environment variable and the reason for leaving a provider out can be a
 * legal one: prod blocks the providers established in countries with no EU
 * adequacy decision, and a blocklist means a hurried {@code LLM_CHAIN_ORDER}
 * edit cannot quietly send European users' assistant messages back to them.
 */
@ConfigurationProperties(prefix = "ai.llm-chain")
public record LlmChainProperties(
        List<String> order,
        List<String> blocked,
        int cooldownRateLimitSeconds,
        int cooldownErrorSeconds,
        Map<String, Provider> providers) {

    public LlmChainProperties {
        order = order == null ? List.of("deepseek") : order;
        blocked = blocked == null ? List.of() : blocked;
        providers = providers == null ? Map.of() : providers;
    }

    /** Case-insensitive, so a stray {@code DeepSeek} in an env var is still blocked. */
    public boolean isBlocked(String provider) {
        return blocked.stream().anyMatch(b -> b.equalsIgnoreCase(provider));
    }

    public record Provider(String baseUrl, String apiKey, String model) {
    }
}
