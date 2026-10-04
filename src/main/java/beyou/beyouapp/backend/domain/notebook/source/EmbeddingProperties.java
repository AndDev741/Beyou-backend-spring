package beyou.beyouapp.backend.domain.notebook.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The ONE embedding model the notebook uses. One, not a chain: vectors from two models live in
 * different spaces and cannot be compared, so falling back to another provider would make every
 * stored vector useless for the question asked. When this model is missing or down, retrieval
 * uses full-text search instead (see {@code NotebookRetriever}).
 *
 * <p>Any OpenAI-compatible {@code /embeddings} endpoint works. The default is Mistral's, keyed by
 * the same {@code MISTRAL_API_KEY} the chat chain already uses. No key: embeddings are off, which
 * is how dev, CI and the e2e stack run.
 */
@ConfigurationProperties(prefix = "notebook.embedding")
public record EmbeddingProperties(String baseUrl, String apiKey, String model, Integer batchSize) {

    public EmbeddingProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.mistral.ai/v1" : baseUrl;
        model = model == null || model.isBlank() ? "mistral-embed" : model;
        batchSize = batchSize == null || batchSize < 1 ? 16 : batchSize;
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
