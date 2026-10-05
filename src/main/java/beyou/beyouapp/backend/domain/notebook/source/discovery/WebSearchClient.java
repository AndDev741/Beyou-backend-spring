package beyou.beyouapp.backend.domain.notebook.source.discovery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the configured provider ({@link DiscoveryProperties}) for web pages about a subject.
 *
 * <p>Tavily takes the query and answers with pages: title, URL and an excerpt. Gemini takes a
 * prompt with Google Search on, and the pages are the answer's grounding chunks, not anything the
 * model wrote: a URL a model types can be invented, a grounding chunk is a page Google returned.
 * Each chunk's excerpt is the part of the answer it supports. The parsing is static so recorded
 * responses can test it.
 */
@Component
@EnableConfigurationProperties(DiscoveryProperties.class)
public class WebSearchClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DiscoveryProperties properties;
    private final RestClient tavily;
    private final RestClient gemini;

    public WebSearchClient(DiscoveryProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(45));
        this.tavily = RestClient.builder().baseUrl(properties.tavilyBaseUrl()).requestFactory(factory).build();
        this.gemini = RestClient.builder().baseUrl(properties.geminiBaseUrl()).requestFactory(factory).build();
    }

    public DiscoveryProperties.Provider provider() {
        return properties.provider();
    }

    /**
     * @param query  a search query, for Tavily
     * @param prompt the same ask as instructions, for Gemini
     */
    public List<SearchHit> search(String query, String prompt) {
        DiscoveryProperties.Provider provider = properties.provider();
        if (provider == null) throw new IllegalStateException("No search provider is configured");
        int max = properties.maxResults();
        if (provider == DiscoveryProperties.Provider.TAVILY) {
            String body = tavily.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(properties.tavilyApiKey()))
                    .body(Map.of("query", query, "max_results", max, "search_depth", "basic",
                            "include_answer", false, "topic", "general"))
                    .retrieve()
                    .body(String.class);
            return parseTavily(body, max);
        }
        String body = gemini.post()
                .uri("/models/{model}:generateContent", properties.geminiModel())
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-goog-api-key", properties.geminiApiKey())
                .body(Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                        "tools", List.of(Map.of("google_search", Map.of()))))
                .retrieve()
                .body(String.class);
        return parseGemini(body, max);
    }

    static List<SearchHit> parseTavily(String body, int max) {
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode result : JSON.readTree(body == null ? "{}" : body).path("results")) {
            String url = result.path("url").asString("");
            if (url.isBlank()) continue;
            hits.add(new SearchHit(result.path("title").asString(""), url, result.path("content").asString("")));
            if (hits.size() >= max) break;
        }
        return hits;
    }

    static List<SearchHit> parseGemini(String body, int max) {
        JsonNode metadata = JSON.readTree(body == null ? "{}" : body)
                .path("candidates").path(0).path("groundingMetadata");
        JsonNode chunks = metadata.path("groundingChunks");
        String[] excerpts = new String[chunks.size()];
        for (JsonNode support : metadata.path("groundingSupports")) {
            String text = support.path("segment").path("text").asString("");
            for (JsonNode index : support.path("groundingChunkIndices")) {
                int i = index.asInt(-1);
                if (i >= 0 && i < excerpts.length && excerpts[i] == null && !text.isBlank()) excerpts[i] = text;
            }
        }
        List<SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < chunks.size() && hits.size() < max; i++) {
            JsonNode web = chunks.path(i).path("web");
            String uri = web.path("uri").asString("");
            if (uri.isBlank()) continue;
            hits.add(new SearchHit(web.path("title").asString(""), uri, excerpts[i] == null ? "" : excerpts[i]));
        }
        return hits;
    }
}
