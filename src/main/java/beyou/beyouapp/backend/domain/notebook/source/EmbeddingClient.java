package beyou.beyouapp.backend.domain.notebook.source;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns text into vectors through {@link EmbeddingProperties}' endpoint.
 *
 * <p>A plain HTTP client rather than Spring AI's embedding model: the starter's embedding
 * autoconfiguration is deliberately kept inert in this app (a noop OpenAI key keeps it booting),
 * and the call is one POST. Throws on any failure; callers decide what a failure means.
 */
@Component
@Slf4j
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingClient {

    private final EmbeddingProperties properties;
    private final RestClient restClient;

    public EmbeddingClient(EmbeddingProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public String model() {
        return properties.model();
    }

    public int batchSize() {
        return properties.batchSize();
    }

    public List<float[]> embed(List<String> inputs) {
        if (!enabled()) {
            throw new IllegalStateException("Embeddings are not configured");
        }
        if (inputs.isEmpty()) return List.of();
        EmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(properties.apiKey()))
                .body(new EmbeddingRequest(properties.model(), inputs))
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null || response.data() == null || response.data().size() != inputs.size()) {
            throw new IllegalStateException("Embedding response did not match the request");
        }
        List<EmbeddingItem> items = new ArrayList<>(response.data());
        items.sort(Comparator.comparingInt(EmbeddingItem::index));
        return items.stream().map(EmbeddingItem::embedding).toList();
    }

    public float[] embedOne(String input) {
        return embed(List.of(input)).get(0);
    }

    record EmbeddingRequest(String model, List<String> input) {
    }

    record EmbeddingResponse(List<EmbeddingItem> data) {
    }

    record EmbeddingItem(int index, float[] embedding) {
    }
}
