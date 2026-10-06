package beyou.beyouapp.backend.domain.notebook.source;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds the passages of a set of sources that best answer a question.
 *
 * <p>Two ways, in order:
 * <ol>
 *   <li>Embeddings, when a model is configured and the sources were embedded by it: the
 *       question is embedded and compared by cosine with every stored vector of those sources, in
 *       the JVM. At the size of one person's sources (thousands of chunks, not millions) that is a
 *       few milliseconds, and it needs nothing from the database but the arrays.</li>
 *   <li>Full-text search on the chunks' generated {@code tsvector}, any word of the question. Used
 *       when there is no model, when the provider fails, or when the sources were embedded by a
 *       different model than the one configured now.</li>
 * </ol>
 * When neither finds anything, the opening passages of each source go instead, so a question like
 * "what is this about?" still has something to stand on.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotebookRetriever {

    /** Vectors scored per question. Past this the oldest sources are skipped for the vector pass. */
    static final int MAX_VECTORS = 6000;

    private final SourceChunkStore chunkStore;
    private final EmbeddingClient embeddingClient;

    public List<SourceChunkStore.StoredChunk> retrieve(Collection<UUID> sourceIds, String question, int limit) {
        if (sourceIds.isEmpty()) return List.of();
        if (embeddingClient.enabled()) {
            try {
                List<SourceChunkStore.ChunkVector> vectors =
                        chunkStore.vectors(sourceIds, embeddingClient.model(), MAX_VECTORS);
                if (!vectors.isEmpty()) {
                    float[] q = embeddingClient.embedOne(question);
                    List<UUID> best = vectors.stream()
                            .map(v -> Map.entry(v.id(), cosine(q, v.vector())))
                            .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                            .limit(limit)
                            .map(Map.Entry::getKey)
                            .toList();
                    return inOrder(best, chunkStore.byIds(best));
                }
            } catch (RuntimeException providerDown) {
                log.warn("Embedding the question failed, using full-text search: {}", providerDown.getMessage());
            }
        }
        List<SourceChunkStore.StoredChunk> found = chunkStore.search(sourceIds, question, limit);
        if (!found.isEmpty()) return found;
        return chunkStore.openings(sourceIds, 2).stream().limit(limit).toList();
    }

    static double cosine(float[] a, float[] b) {
        if (a.length == 0 || a.length != b.length) return -1;
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? -1 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static List<SourceChunkStore.StoredChunk> inOrder(List<UUID> order, List<SourceChunkStore.StoredChunk> chunks) {
        Map<UUID, SourceChunkStore.StoredChunk> byId = new LinkedHashMap<>();
        chunks.forEach(c -> byId.put(c.id(), c));
        List<SourceChunkStore.StoredChunk> out = new ArrayList<>();
        for (UUID id : order) {
            SourceChunkStore.StoredChunk chunk = byId.get(id);
            if (chunk != null) out.add(chunk);
        }
        out.sort(Comparator.comparingInt(c -> order.indexOf(c.id())));
        return out;
    }
}
