package com.projectardor.knowledge.repository;

import java.sql.Timestamp;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.projectardor.knowledge.domain.KnowledgeSourceType;

/**
 * pgvector access for knowledge chunks.
 *
 * <p>Written against plain JDBC on purpose: pgvector accepts its text input format
 * ({@code '[1,2,3]'::vector}), so no extra client library is needed.
 *
 * <p>Every statement here is scoped by {@code user_id} first, per the isolation rule in
 * docs/AI_GUIDE.md ("向量检索必须先按 user_id 过滤，再计算相似度"). The dimension filter is
 * what makes a dimension-less vector column safe: only vectors produced by a model with the
 * same output size ever reach the distance operator.
 */
@Repository
public class KnowledgeVectorStore {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Stores one chunk's embedding. */
    public void saveEmbedding(UUID userId, UUID chunkId, String vectorLiteral, int dimension, String model) {
        jdbcTemplate.update("""
                UPDATE knowledge_chunks
                SET embedding = ?::vector,
                    embedding_dim = ?,
                    embedding_model = ?,
                    embedded_at = ?
                WHERE id = ? AND user_id = ?
                """,
                vectorLiteral, dimension, model, Timestamp.from(Instant.now()), chunkId, userId);
    }

    /**
     * Nearest neighbours for one query vector, restricted to the caller's own chunks.
     * Similarity is cosine, mapped to [0,1] where 1 is identical.
     */
    public List<VectorHit> search(UUID userId, String queryVectorLiteral, int dimension, int limit) {
        return jdbcTemplate.query("""
                SELECT id, document_id, content, 1 - (embedding <=> ?::vector) AS similarity
                FROM knowledge_chunks
                WHERE user_id = ?
                  AND embedding IS NOT NULL
                  AND embedding_dim = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """,
                (rs, rowNum) -> new VectorHit(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("content"),
                        rs.getDouble("similarity")),
                queryVectorLiteral, userId, dimension, queryVectorLiteral, limit);
    }

    /** Scores lexical candidates in PostgreSQL so the application never loads every chunk. */
    public List<LexicalHit> lexicalSearch(UUID userId, List<String> terms, int limit) {
        if (terms.isEmpty()) return List.of();
        String sql = """
                WITH terms AS (SELECT term FROM unnest(?::text[]) AS term)
                SELECT c.document_id, d.title, d.source_type, d.source_url, c.content,
                       SUM(
                         ((length(lower(c.content)) - length(replace(lower(c.content), term, '')))
                           / greatest(length(term), 1))
                           * least(4.0, greatest(1.0, length(term) / 2.0))
                         + CASE WHEN lower(d.title) LIKE ('%' || term || '%') THEN 5.0 ELSE 0.0 END
                       ) AS score
                FROM knowledge_chunks c
                JOIN knowledge_documents d ON d.id = c.document_id AND d.user_id = c.user_id
                CROSS JOIN terms
                WHERE c.user_id = ?
                  AND (lower(c.content) LIKE ('%' || term || '%')
                       OR lower(d.title) LIKE ('%' || term || '%'))
                GROUP BY c.id, c.document_id, d.title, d.source_type, d.source_url, c.content
                ORDER BY score DESC, c.created_at DESC
                LIMIT ?
                """;
        return jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setArray(1, connection.createArrayOf("text", terms.toArray()));
            statement.setObject(2, userId);
            statement.setInt(3, limit);
            return statement;
        }, (rs, rowNum) -> new LexicalHit(
                rs.getObject("document_id", UUID.class),
                rs.getString("title"),
                KnowledgeSourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_url"),
                rs.getString("content"),
                rs.getDouble("score")));
    }

    /** Chunks belonging to this user that still have no embedding, oldest first. */
    public List<PendingChunk> findPending(UUID userId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, content
                FROM knowledge_chunks
                WHERE user_id = ? AND embedding IS NULL
                ORDER BY created_at
                LIMIT ?
                """,
                (rs, rowNum) -> new PendingChunk(rs.getObject("id", UUID.class), rs.getString("content")),
                userId, limit);
    }

    /** Users that currently have chunks waiting for an embedding, oldest work first. */
    public List<UUID> findUsersWithPendingChunks(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT user_id
                FROM knowledge_chunks
                WHERE embedding IS NULL
                GROUP BY user_id
                ORDER BY min(created_at)
                LIMIT ?
                """, UUID.class, limit);
    }

    public long countPending(UUID userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_chunks WHERE user_id = ? AND embedding IS NULL",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    public long countEmbedded(UUID userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_chunks WHERE user_id = ? AND embedding IS NOT NULL",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    /**
     * Drops embeddings that were produced by a different model than the one currently
     * configured, so a model switch re-indexes instead of silently mixing vector spaces.
     */
    public int clearEmbeddingsForOtherModels(UUID userId, String currentModel) {
        return jdbcTemplate.update("""
                UPDATE knowledge_chunks
                SET embedding = NULL, embedding_dim = NULL, embedding_model = NULL, embedded_at = NULL
                WHERE user_id = ? AND embedding IS NOT NULL AND embedding_model IS DISTINCT FROM ?
                """, userId, currentModel);
    }

    public record VectorHit(UUID chunkId, UUID documentId, String content, double similarity) {
    }

    public record LexicalHit(UUID documentId, String title, KnowledgeSourceType sourceType,
            String sourceUrl, String content, double score) {
    }

    public record PendingChunk(UUID chunkId, String content) {
    }
}
