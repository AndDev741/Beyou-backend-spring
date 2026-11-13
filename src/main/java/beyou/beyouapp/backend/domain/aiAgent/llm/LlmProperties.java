package beyou.beyouapp.backend.domain.aiAgent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one LLM endpoint behind every AI feature: the assistant, onboarding, the study
 * notebook and the briefing prose. Any OpenAI-compatible server works.
 *
 * <p>This app keeps no provider list and no fallback order. Picking a provider, moving to
 * the next one when it fails and resting one that is out of quota all happen in the gateway
 * at {@code baseUrl}, which in production is an OmniRoute combo.
 */
@ConfigurationProperties(prefix = "ai.llm")
public record LlmProperties(String baseUrl, String apiKey, String model) {

    /** All three are needed to make a call, so a partly filled endpoint counts as unset. */
    public boolean isConfigured() {
        return hasText(baseUrl) && hasText(apiKey) && hasText(model);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
