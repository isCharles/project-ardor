package com.projectardor.agent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import com.projectardor.agent.AgentContextReference;
import com.projectardor.agent.domain.Conversation;
import com.projectardor.agent.domain.ConversationMessage;
import com.projectardor.agent.web.AgentContextReferenceRequest;
import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class AgentRunStore {

    private static final RowMapper<AgentRun> RUN_MAPPER = (rs, rowNum) -> new AgentRun(
            rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
            rs.getString("message"), rs.getString("status"), rs.getString("label"),
            rs.getString("error_code"), rs.getString("error_message"), rs.getBoolean("retryable"),
            rs.getBoolean("tool_started"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;
    private final AgentConversationStore conversations;
    private final EntityManager entityManager;

    public AgentRunStore(JdbcTemplate jdbc, AgentConversationStore conversations, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.entityManager = entityManager;
    }

    /** The UUID is supplied by the browser, but ownership and request contents are checked on every reuse. */
    public boolean begin(UUID userId, UUID requestId, UUID conversationId, String message,
            String contextType, UUID contextId, List<AgentContextReferenceRequest> references) {
        String hash = fingerprint(conversationId, message, contextType, contextId, references);
        int inserted = jdbc.update("""
                INSERT INTO agent_runs (id, user_id, conversation_id, request_hash, message)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, requestId, userId, conversationId, hash, message);
        if (inserted == 0) {
            List<String> hashes = jdbc.queryForList(
                    "SELECT request_hash FROM agent_runs WHERE id = ? AND user_id = ?", String.class,
                    requestId, userId);
            if (hashes.isEmpty() || !hash.equals(hashes.get(0))) {
                throw new IllegalArgumentException("请求标识已用于另一条消息");
            }
        }
        return inserted == 1;
    }

    public AgentRun get(UUID userId, UUID requestId) {
        return jdbc.query("""
                SELECT id, conversation_id, message, status, label, error_code, error_message,
                       retryable, tool_started, created_at, updated_at
                FROM agent_runs WHERE id = ? AND user_id = ?
                """, RUN_MAPPER, requestId, userId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("请求记录不存在"));
    }

    public Optional<AgentRun> latest(UUID userId, UUID conversationId) {
        return jdbc.query("""
                SELECT id, conversation_id, message, status, label, error_code, error_message,
                       retryable, tool_started, created_at, updated_at
                FROM agent_runs WHERE user_id = ? AND conversation_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, RUN_MAPPER, userId, conversationId).stream().findFirst();
    }

    public void progress(UUID userId, UUID requestId, String label, boolean toolStarted) {
        jdbc.update("""
                UPDATE agent_runs SET label = ?, tool_started = tool_started OR ?,
                       updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, label, toolStarted, requestId, userId);
    }

    public void heartbeat(UUID userId, UUID requestId) {
        jdbc.update("""
                UPDATE agent_runs SET updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, requestId, userId);
    }

    @Transactional
    public List<ConversationMessage> complete(UUID userId, UUID requestId, Conversation conversation,
            String message, String answer, String trace, List<AgentContextReference> references) {
        if (!"RUNNING".equals(get(userId, requestId).status())) {
            throw new IllegalStateException("请求已结束，不能再次保存回复");
        }
        List<ConversationMessage> saved = conversations.appendExchange(
                userId, conversation, message, answer, trace, references);
        // JDBC's owner-scoped message FK is immediate; flush JPA inserts before referencing the assistant row.
        entityManager.flush();
        int updated = jdbc.update("""
                UPDATE agent_runs SET status = 'COMPLETED', label = '已完成',
                       assistant_message_id = ?, updated_at = CURRENT_TIMESTAMP,
                       finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, saved.get(1).getId(), requestId, userId);
        if (updated != 1) throw new IllegalStateException("请求状态已改变，回复未保存");
        return saved;
    }

    public AgentRun fail(UUID userId, UUID requestId, String code, String message, boolean retryable) {
        jdbc.update("""
                UPDATE agent_runs SET status = 'FAILED', label = '执行失败', error_code = ?,
                       error_message = ?, retryable = CASE WHEN tool_started THEN FALSE ELSE ? END,
                       updated_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, code, message, retryable, requestId, userId);
        return get(userId, requestId);
    }

    /** A dead server cannot finish a run; do not let it remain "running" forever. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void interruptStaleRuns() {
        jdbc.update("""
                UPDATE agent_runs SET status = 'INTERRUPTED', label = '状态待确认',
                       error_code = 'AGENT_RUN_INTERRUPTED',
                       error_message = '长时间未收到执行进展，无法确认操作是否已完成；请检查日历等操作结果后再试',
                       retryable = FALSE, updated_at = CURRENT_TIMESTAMP,
                       finished_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '30 minutes'
                """);
    }

    private String fingerprint(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> references) {
        StringBuilder input = new StringBuilder().append(conversationId).append('\n')
                .append(message.length()).append(':').append(message).append('\n')
                .append(contextType).append(':').append(contextId);
        if (references != null) {
            for (AgentContextReferenceRequest reference : references) {
                input.append('\n').append(reference.type()).append(':').append(reference.id());
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record AgentRun(UUID id, UUID conversationId, String message, String status, String label,
            String errorCode, String errorMessage, boolean retryable, boolean toolStarted,
            Instant createdAt, Instant updatedAt) {
    }
}
