package com.projectardor.assistant.memory.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** User-scoped pgvector storage for long-term memories. */
@Service
public class MemoryVectorStore {

    public record Match(UUID id, double similarity) {}
    public record Pending(UUID id, String content) {}

    private final JdbcTemplate jdbc;

    public MemoryVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveEmbedding(UUID userId, UUID itemId, String expectedContent, String vectorLiteral, int dimension, String model) {
        jdbc.update("""
                UPDATE user_agent_memory_items
                SET embedding = ?::vector, embedding_dim = ?, embedding_model = ?, embedded_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND content = ? AND valid_until IS NULL
                """, vectorLiteral, dimension, model, itemId, userId, expectedContent);
    }

    /** Cosine matches for one query, restricted to the user's current model and dimension. */
    public List<Match> similar(UUID userId, String vectorLiteral, int dimension, String model, int limit) {
        return jdbc.query("""
                SELECT id, 1 - (embedding <=> ?::vector) AS similarity
                FROM user_agent_memory_items
                WHERE user_id = ? AND valid_until IS NULL AND embedding IS NOT NULL
                  AND embedding_dim = ? AND embedding_model = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """, (resultSet, index) -> new Match(resultSet.getObject("id", UUID.class),
                        resultSet.getDouble("similarity")),
                vectorLiteral, userId, dimension, model, vectorLiteral, limit);
    }

    public List<UUID> usersWithStaleEmbeddings(int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT m.user_id FROM user_agent_memory_items m
                LEFT JOIN auxiliary_api_configs c
                  ON c.user_id = m.user_id AND c.service_type = 'EMBEDDING'
                LEFT JOIN system_api_configs s ON s.service_type = 'EMBEDDING'
                WHERE m.valid_until IS NULL
                  AND COALESCE(c.model, s.model) IS NOT NULL
                  AND (m.embedding IS NULL OR m.embedding_model IS DISTINCT FROM COALESCE(c.model, s.model))
                LIMIT ?
                """, UUID.class, limit);
    }

    public List<UUID> usersWithMemories(int limit) {
        return usersWithMemoriesAfter(null, limit);
    }

    public List<UUID> usersWithMemoriesAfter(UUID after, int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT user_id FROM user_agent_memory_items
                WHERE valid_until IS NULL AND (?::uuid IS NULL OR user_id > ?::uuid)
                ORDER BY user_id
                LIMIT ?
                """, UUID.class, after, after, limit);
    }

    /** Rows missing a vector, or embedded by a model that is no longer configured. */
    public List<Pending> pendingForUser(UUID userId, String model, int limit) {
        return jdbc.query("""
                SELECT id, content FROM user_agent_memory_items
                WHERE user_id = ? AND valid_until IS NULL
                  AND (embedding IS NULL OR embedding_model IS DISTINCT FROM ?)
                ORDER BY created_at
                LIMIT ?
                """, (resultSet, index) -> new Pending(resultSet.getObject("id", UUID.class),
                        resultSet.getString("content")), userId, model, limit);
    }

    /** Closes low-confidence inferred facts that have aged out; explicit memories are never touched. */
    public int expireLowConfidence(UUID userId, double confidenceBelow, int olderThanDays) {
        return jdbc.update("""
                UPDATE user_agent_memory_items
                SET valid_until = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND valid_until IS NULL AND source_kind = 'INFERRED'
                  AND confidence < ?
                  AND COALESCE(valid_from, created_at) < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                """, userId, confidenceBelow, olderThanDays);
    }
}
