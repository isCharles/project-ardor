package com.projectardor.agent.repository;

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

    public void saveEmbedding(UUID userId, UUID itemId, String vectorLiteral, int dimension, String model) {
        jdbc.update("""
                UPDATE user_agent_memory_items
                SET embedding = ?::vector, embedding_dim = ?, embedding_model = ?, embedded_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ?
                """, vectorLiteral, dimension, model, itemId, userId);
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

    public List<UUID> usersWithPending(int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT user_id FROM user_agent_memory_items
                WHERE embedding IS NULL AND valid_until IS NULL
                LIMIT ?
                """, UUID.class, limit);
    }

    public List<Pending> pendingForUser(UUID userId, int limit) {
        return jdbc.query("""
                SELECT id, content FROM user_agent_memory_items
                WHERE user_id = ? AND valid_until IS NULL AND embedding IS NULL
                ORDER BY created_at
                LIMIT ?
                """, (resultSet, index) -> new Pending(resultSet.getObject("id", UUID.class),
                        resultSet.getString("content")), userId, limit);
    }
}
