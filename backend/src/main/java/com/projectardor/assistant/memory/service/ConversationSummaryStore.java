package com.projectardor.assistant.memory.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Durable, owner-scoped prompt summaries. Raw conversation messages are never deleted. */
@Service
public class ConversationSummaryStore {
    private final JdbcTemplate jdbc;

    public ConversationSummaryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Summary> latest(UUID userId, UUID conversationId) {
        return jdbc.query("""
                SELECT id, version, covered_message_id, covered_count, content, token_count, model, created_at
                FROM conversation_summaries
                WHERE user_id = ? AND conversation_id = ?
                ORDER BY version DESC
                LIMIT 1
                """, (rs, row) -> new Summary(
                        rs.getObject("id", UUID.class),
                        rs.getInt("version"),
                        rs.getObject("covered_message_id", UUID.class),
                        rs.getInt("covered_count"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        rs.getString("model"),
                        rs.getTimestamp("created_at").toInstant()),
                userId, conversationId).stream().findFirst();
    }

    public Summary save(UUID userId, UUID conversationId, int version, UUID coveredMessageId,
            int coveredCount, String content, int tokenCount, String model) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO conversation_summaries
                    (id, user_id, conversation_id, version, covered_message_id, covered_count,
                     content, token_count, model)
                SELECT ?, c.user_id, c.id, ?, m.id, ?, ?, ?, ?
                FROM conversations c JOIN messages m
                    ON m.conversation_id = c.id AND m.user_id = c.user_id
                WHERE c.id = ? AND c.user_id = ? AND m.id = ?
                """, id, version, coveredCount, content, tokenCount, model,
                conversationId, userId, coveredMessageId);
        if (inserted != 1) throw new com.projectardor.common.web.ResourceNotFoundException("会话或摘要边界消息不存在");
        return new Summary(id, version, coveredMessageId, coveredCount, content, tokenCount, model, Instant.now());
    }

    public record Summary(UUID id, int version, UUID coveredMessageId, int coveredCount,
            String content, int tokenCount, String model, Instant createdAt) {}
}
