package beyou.beyouapp.backend.domain.notebook.source;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Reads and writes {@code notebook_source_chunks} with plain SQL.
 *
 * <p>No JPA entity, on purpose. The table has a generated {@code tsvector} and a {@code real[]},
 * two types Hibernate would have to be taught to validate and that nothing here needs as objects.
 * Every query names its columns, so the generated column is never written.
 */
@Component
@RequiredArgsConstructor
public class SourceChunkStore {

    private final JdbcTemplate jdbc;

    /** A chunk as the study AI quotes it. */
    public record StoredChunk(UUID id, UUID sourceId, int ordinal, Integer pageNumber, String content) {
    }

    /** A chunk id and its vector, for scoring. */
    public record ChunkVector(UUID id, float[] vector) {
    }

    private static final RowMapper<StoredChunk> CHUNK = (rs, i) -> new StoredChunk(
            rs.getObject("id", UUID.class), rs.getObject("source_id", UUID.class), rs.getInt("ordinal"),
            (Integer) rs.getObject("page_number"), rs.getString("content"));

    public List<UUID> insert(UUID sourceId, UUID userId, List<TextChunker.Chunk> chunks) {
        List<UUID> ids = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (TextChunker.Chunk chunk : chunks) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            rows.add(new Object[] {id, sourceId, userId, chunk.ordinal(), chunk.pageNumber(), chunk.content()});
        }
        jdbc.batchUpdate("INSERT INTO notebook_source_chunks (id, source_id, user_id, ordinal, page_number, content) "
                + "VALUES (?, ?, ?, ?, ?, ?)", rows);
        return ids;
    }

    public void setEmbeddings(List<UUID> ids, List<float[]> vectors, String model) {
        jdbc.batchUpdate("UPDATE notebook_source_chunks SET embedding = ?, embedding_model = ? WHERE id = ?",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        float[] vector = vectors.get(i);
                        Float[] boxed = new Float[vector.length];
                        for (int k = 0; k < vector.length; k++) boxed[k] = vector[k];
                        Array array = ps.getConnection().createArrayOf("float4", boxed);
                        ps.setArray(1, array);
                        ps.setString(2, model);
                        ps.setObject(3, ids.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return ids.size();
                    }
                });
    }

    public List<StoredChunk> forSource(UUID sourceId) {
        return jdbc.query("SELECT id, source_id, ordinal, page_number, content FROM notebook_source_chunks "
                + "WHERE source_id = ? ORDER BY ordinal", CHUNK, sourceId);
    }

    public List<StoredChunk> byIds(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("SELECT id, source_id, ordinal, page_number, content FROM notebook_source_chunks "
                + "WHERE id = ANY(?)", ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                CHUNK);
    }

    /** One chunk with its neighbours, for "Open at page N". */
    public List<StoredChunk> around(UUID sourceId, int ordinal) {
        return jdbc.query("SELECT id, source_id, ordinal, page_number, content FROM notebook_source_chunks "
                + "WHERE source_id = ? AND ordinal BETWEEN ? AND ? ORDER BY ordinal",
                CHUNK, sourceId, ordinal - 1, ordinal + 1);
    }

    /** The vectors made by {@code model} for these sources, at most {@code limit} of them. */
    public List<ChunkVector> vectors(Collection<UUID> sourceIds, String model, int limit) {
        if (sourceIds.isEmpty()) return List.of();
        return jdbc.query("SELECT id, embedding FROM notebook_source_chunks "
                + "WHERE source_id = ANY(?) AND embedding_model = ? AND embedding IS NOT NULL LIMIT ?",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", sourceIds.toArray()));
                    ps.setString(2, model);
                    ps.setInt(3, limit);
                },
                (ResultSet rs, int i) -> new ChunkVector(rs.getObject("id", UUID.class), toFloats(rs.getArray("embedding"))));
    }

    /**
     * Full-text match, any word of the question, best first. OR rather than plainto_tsquery's AND:
     * a question in natural language almost never has every one of its words in one passage.
     */
    public List<StoredChunk> search(Collection<UUID> sourceIds, String question, int limit) {
        String terms = Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() >= 3)
                .distinct()
                .limit(24)
                .collect(Collectors.joining(" | "));
        if (sourceIds.isEmpty() || terms.isEmpty()) return List.of();
        return jdbc.query("SELECT id, source_id, ordinal, page_number, content FROM notebook_source_chunks "
                + "WHERE source_id = ANY(?) AND search @@ to_tsquery('simple', ?) "
                + "ORDER BY ts_rank(search, to_tsquery('simple', ?)) DESC, ordinal LIMIT ?",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", sourceIds.toArray()));
                    ps.setString(2, terms);
                    ps.setString(3, terms);
                    ps.setInt(4, limit);
                },
                CHUNK);
    }

    /** The opening chunks of each source: the last resort when nothing matches the question. */
    public List<StoredChunk> openings(Collection<UUID> sourceIds, int perSource) {
        if (sourceIds.isEmpty()) return List.of();
        return jdbc.query("SELECT id, source_id, ordinal, page_number, content FROM notebook_source_chunks "
                + "WHERE source_id = ANY(?) AND ordinal < ? ORDER BY source_id, ordinal",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", sourceIds.toArray()));
                    ps.setInt(2, perSource);
                },
                CHUNK);
    }

    private static float[] toFloats(Array array) throws SQLException {
        if (array == null) return new float[0];
        Object raw = array.getArray();
        if (raw instanceof Float[] boxed) {
            float[] out = new float[boxed.length];
            for (int i = 0; i < boxed.length; i++) out[i] = boxed[i] == null ? 0f : boxed[i];
            return out;
        }
        if (raw instanceof float[] floats) return floats;
        Object[] objects = (Object[]) raw;
        float[] out = new float[objects.length];
        for (int i = 0; i < objects.length; i++) out[i] = ((Number) objects[i]).floatValue();
        return out;
    }
}
