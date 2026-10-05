package beyou.beyouapp.backend.domain.notebook.source.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The web search behind "find sources for me". Two providers, and the keys pick one: Tavily when
 * {@code TAVILY_API_KEY} is set, otherwise Gemini with Google Search grounding when
 * {@code NOTEBOOK_DISCOVERY_GEMINI_API_KEY} is, otherwise none and the feature is off (the study
 * room hides the button).
 *
 * <p>Tavily wins because it answers with the pages themselves, while Gemini answers with Google's
 * redirect links that have to be followed first. Gemini's key is its own and does not fall back
 * to the chat chain's {@code GEMINI_API_KEY}: Google Search needs a project with billing, and on
 * a free key every grounded call answers 429, which is how this was found. A fallback would put a
 * button in front of people that fails every time.
 */
@ConfigurationProperties(prefix = "notebook.discovery")
public record DiscoveryProperties(
        String tavilyApiKey,
        String tavilyBaseUrl,
        String geminiApiKey,
        String geminiModel,
        String geminiBaseUrl,
        Integer maxResults) {

    public enum Provider { TAVILY, GEMINI }

    public DiscoveryProperties {
        tavilyBaseUrl = blank(tavilyBaseUrl) ? "https://api.tavily.com" : tavilyBaseUrl;
        geminiModel = blank(geminiModel) ? "gemini-flash-latest" : geminiModel;
        geminiBaseUrl = blank(geminiBaseUrl) ? "https://generativelanguage.googleapis.com/v1beta" : geminiBaseUrl;
        maxResults = maxResults == null || maxResults < 1 ? 8 : Math.min(maxResults, 12);
    }

    /** The provider the keys pick, or null when discovery is off. */
    public Provider provider() {
        if (!blank(tavilyApiKey)) return Provider.TAVILY;
        if (!blank(geminiApiKey)) return Provider.GEMINI;
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
