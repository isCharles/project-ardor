package com.projectardor.agent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import com.projectardor.agent.AgentContextReference;
import com.projectardor.agent.ReasoningDepth;
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
    private final ConcurrentHashMap<UUID, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    public AgentRunStore(JdbcTemplate jdbc, AgentConversationStore conversations, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.entityManager = entityManager;
    }

    /** The UUID is supplied by the browser, but ownership and request contents are checked on every reuse. */
    public boolean begin(UUID userId, UUID requestId, UUID conversationId, String message,
            String contextType, UUID contextId, List<AgentContextReferenceRequest> references) {
        return begin(userId, requestId, conversationId, message, contextType, contextId, references, null);
    }

    public UUID logicalActionId(UUID userId, UUID requestId, UUID conversationId, String message,
            String contextType, UUID contextId, List<AgentContextReferenceRequest> references) {
        return logicalActionId(userId, requestId, conversationId, message, contextType, contextId, references, ReasoningDepth.AUTO);
    }

    public UUID logicalActionId(UUID userId, UUID requestId, UUID conversationId, String message,
            String contextType, UUID contextId, List<AgentContextReferenceRequest> references, ReasoningDepth depth) {
        String hash = fingerprint(conversationId, message, contextType, contextId, references, depth, steeringSource(userId, requestId));
        return jdbc.queryForList("""
                SELECT CASE WHEN status IN ('INTERRUPTED', 'FAILED')
                                 AND (tool_started OR (logical_action_id IS NOT NULL AND logical_action_id <> id))
                                 AND request_hash = ?
                            THEN COALESCE(logical_action_id, id) ELSE ? END
                FROM agent_runs WHERE user_id = ? AND conversation_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, UUID.class, hash, requestId, userId, conversationId).stream().findFirst().orElse(requestId);
    }

    public boolean begin(UUID userId, UUID requestId, UUID conversationId, String message,
            String contextType, UUID contextId, List<AgentContextReferenceRequest> references,
            UUID logicalActionId) {
        return begin(userId, requestId, conversationId, message, contextType, contextId, references, logicalActionId, ReasoningDepth.AUTO);
    }

    public boolean begin(UUID userId, UUID requestId, UUID conversationId, String message,
            String contextType, UUID contextId, List<AgentContextReferenceRequest> references,
            UUID logicalActionId, ReasoningDepth depth) {
        return begin(userId, requestId, conversationId, message, contextType, contextId, references, logicalActionId, depth, List.of());
    }

    public boolean begin(UUID userId, UUID requestId, UUID conversationId, String message,
            String contextType, UUID contextId, List<AgentContextReferenceRequest> references,
            UUID logicalActionId, ReasoningDepth depth, List<AgentContextReference> resolvedReferences) {
        String hash = fingerprint(conversationId, message, contextType, contextId, references, depth, steeringSource(userId, requestId));
        String contextJson = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(resolvedReferences);
        int inserted = logicalActionId == null
                ? jdbc.update("""
                    INSERT INTO agent_runs (id, user_id, conversation_id, request_hash, message, context_references)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                    ON CONFLICT (id) DO NOTHING
                    """, requestId, userId, conversationId, hash, message, contextJson)
                : jdbc.update("""
                    INSERT INTO agent_runs (id, user_id, conversation_id, request_hash, message, logical_action_id, context_references)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    ON CONFLICT (id) DO NOTHING
                    """, requestId, userId, conversationId, hash, message, logicalActionId, contextJson);
        if (inserted == 0) {
            List<String> hashes = jdbc.queryForList(
                    "SELECT request_hash FROM agent_runs WHERE id = ? AND user_id = ?", String.class,
                    requestId, userId);
            if (hashes.isEmpty() || !hash.equals(hashes.get(0))) {
                throw new IllegalArgumentException("请求标识已用于另一条消息");
            }
        }
        if (inserted == 1) activeRuns.put(requestId, new ActiveRun(userId, Instant.now()));
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

    public boolean hasEarlierQueuedMessage(UUID userId, UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_message_queue q
                  WHERE q.user_id=? AND q.status IN ('QUEUED','DISPATCHING','RUNNING')
                    AND q.id IS DISTINCT FROM ? AND q.sequence < COALESCE(
                      (SELECT sequence FROM agent_message_queue WHERE id=? AND user_id=?),9223372036854775807))
                """, Boolean.class, userId, requestId, requestId, userId));
    }

    public Optional<AgentRun> latest(UUID userId, UUID conversationId) {
        return jdbc.query("""
                SELECT id, conversation_id, message, status, label, error_code, error_message,
                       retryable, tool_started, created_at, updated_at
                FROM agent_runs WHERE user_id = ? AND conversation_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, RUN_MAPPER, userId, conversationId).stream().findFirst();
    }

    /** Accepted-but-uncompleted user turns are durable even without a successful exchange. */
    public List<AgentRun> unfinished(UUID userId, UUID conversationId, boolean recent) {
        return jdbc.query("""
                SELECT r.* FROM agent_runs r WHERE r.user_id=? AND r.conversation_id=?
                  AND r.status IN ('FAILED','INTERRUPTED') AND r.assistant_message_id IS NULL
                  AND NOT (r.status='FAILED' AND r.error_code IS DISTINCT FROM 'AGENT_RUN_CANCELLED' AND NOT r.tool_started AND EXISTS(
                    SELECT 1 FROM agent_runs newer WHERE newer.user_id=r.user_id AND newer.conversation_id=r.conversation_id
                      AND newer.request_hash=r.request_hash AND newer.created_at>r.created_at
                      AND newer.created_at<r.created_at+INTERVAL '2 minutes'))
                ORDER BY r.created_at DESC,r.id DESC
                """ + (recent ? " LIMIT 40" : ""), RUN_MAPPER, userId, conversationId).reversed();
    }

    public List<AgentRun> superseded(UUID userId, UUID conversationId, boolean recent) {
        return jdbc.query("""
                SELECT q.id,q.conversation_id,q.payload->>'message' AS message,q.status,
                  '未执行' AS label,CASE WHEN q.status='CANCELLED' THEN 'SUPERSEDED' ELSE 'QUEUE_NOT_STARTED' END AS error_code,
                  q.error_message,false AS retryable,false AS tool_started,q.created_at,q.updated_at
                FROM agent_message_queue q WHERE q.user_id=? AND q.conversation_id=?
                  AND (q.status IN ('FAILED','INTERRUPTED') OR (q.status='CANCELLED' AND q.error_message='已由后续补充替代，未执行'))
                  AND NOT EXISTS(SELECT 1 FROM agent_runs r WHERE r.id=q.id AND r.user_id=q.user_id)
                ORDER BY q.created_at DESC,q.id DESC
                """ + (recent ? " LIMIT 40" : ""), RUN_MAPPER, userId, conversationId).reversed();
    }

    public List<AgentContextReference> contextReferences(UUID userId, UUID conversationId, UUID requestId) {
        var json = jdbc.queryForList("SELECT context_references::text FROM agent_runs WHERE id=? AND user_id=? AND conversation_id=?",
                String.class, requestId, userId, conversationId).stream().findFirst().orElse("[]");
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var references = mapper.readValue(json, new tools.jackson.core.type.TypeReference<List<AgentContextReference>>() {});
        if (!references.isEmpty()) return references;
        // Queue payloads also cover accepted messages that failed before a run existed.
        var payload = jdbc.queryForList("SELECT payload::text FROM agent_message_queue WHERE id=? AND user_id=? AND conversation_id=?",
                String.class, requestId, userId, conversationId).stream().findFirst();
        if (payload.isEmpty()) return List.of();
        var request = mapper.readValue(payload.get(), com.projectardor.agent.web.AgentMessageRequest.class);
        var result = new java.util.LinkedHashMap<String, AgentContextReference>();
        if (request.contextType() != null && request.contextId() != null) {
            var ref = new AgentContextReference(request.contextType(), request.contextId(), request.contextType());
            result.put(ref.type() + ":" + ref.id(), ref);
        }
        if (request.contextReferences() != null) for (var ref : request.contextReferences()) {
            result.put(ref.type() + ":" + ref.id(), new AgentContextReference(ref.type(), ref.id(), ref.type()));
        }
        return List.copyOf(result.values());
    }

    /** Scope comes only from an owned, explicitly linked interrupted request, never model output. */
    public String interruptedRequest(UUID userId, UUID conversationId, UUID currentRequest) {
        if (currentRequest == null) return "";
        return jdbc.queryForList("""
                SELECT r.message FROM agent_message_queue q JOIN agent_runs r
                  ON r.id::text=COALESCE(q.payload->>'interruptedRunId',
                    (SELECT p.payload->>'interruptedRunId' FROM agent_message_queue p
                     WHERE p.user_id=q.user_id AND p.conversation_id=q.conversation_id AND p.created_at<q.created_at
                       AND p.sequence<0 AND p.created_at>q.created_at-INTERVAL '10 minutes'
                       AND p.payload->>'interruptedRunId' IS NOT NULL ORDER BY p.created_at DESC LIMIT 1))
                  AND r.user_id=q.user_id AND r.conversation_id=q.conversation_id
                WHERE q.id=? AND q.user_id=? AND q.conversation_id=? AND q.sequence<0
                  AND r.status IN ('FAILED','INTERRUPTED') AND r.error_code IN ('AGENT_RUN_CANCELLED','AGENT_RUN_INTERRUPTED')
                """, String.class, currentRequest, userId, conversationId).stream().findFirst().orElse("");
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
        if (!List.of("RUNNING", "INTERRUPTED").contains(get(userId, requestId).status())) {
            throw new IllegalStateException("请求已结束，不能再次保存回复");
        }
        List<ConversationMessage> saved = conversations.appendExchange(
                userId, conversation, message, answer, trace, references);
        // JDBC's owner-scoped message FK is immediate; flush JPA inserts before referencing the assistant row.
        entityManager.flush();
        int updated = jdbc.update("""
                UPDATE agent_runs SET status = 'COMPLETED', label = '已完成',
                       error_code = NULL, error_message = NULL, retryable = FALSE,
                       assistant_message_id = ?, updated_at = CURRENT_TIMESTAMP,
                       finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('RUNNING', 'INTERRUPTED')
                """, saved.get(1).getId(), requestId, userId);
        if (updated != 1) throw new IllegalStateException("请求状态已改变，回复未保存");
        activeRuns.remove(requestId);
        return saved;
    }

    public AgentRun fail(UUID userId, UUID requestId, String code, String message, boolean retryable) {
        jdbc.update("""
                UPDATE agent_runs SET status = 'FAILED', label = '执行失败', error_code = ?,
                       error_message = ?, retryable = CASE WHEN tool_started THEN FALSE ELSE ? END,
                       updated_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, code, message, retryable, requestId, userId);
        activeRuns.remove(requestId);
        return get(userId, requestId);
    }

    /** Keep this process's live model and tool calls distinguishable from runs orphaned by a crash. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void heartbeatActiveRuns() {
        Instant limit = Instant.now().minusSeconds(2 * 60 * 60);
        activeRuns.forEach((requestId, active) -> {
            if (active.startedAt().isBefore(limit)) activeRuns.remove(requestId, active);
            else heartbeat(active.userId(), requestId);
        });
    }

    /** A dead server cannot finish a run; do not let it remain "running" forever. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void interruptStaleRuns() {
        List<UUID> candidates = jdbc.queryForList("SELECT id FROM agent_runs WHERE status='RUNNING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '2 minutes'", UUID.class);
        for (UUID requestId : candidates) {
            if (activeRuns.containsKey(requestId)) continue;
        jdbc.update("""
                UPDATE agent_runs SET status = 'INTERRUPTED', label = '状态待确认',
                       error_code = 'AGENT_RUN_INTERRUPTED',
                       error_message = '长时间未收到执行进展，无法确认操作是否已完成；请检查日历等操作结果后再试',
                       retryable = FALSE, updated_at = CURRENT_TIMESTAMP,
                       finished_at = CURRENT_TIMESTAMP
                WHERE id=? AND status = 'RUNNING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '2 minutes'
                """, requestId);
        }
    }

    private String steeringSource(UUID owner, UUID requestId) {
        return jdbc.queryForList("SELECT payload->>'interruptedRunId' FROM agent_message_queue WHERE id=? AND user_id=?", String.class, requestId, owner)
                .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }
    private String fingerprint(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> references, ReasoningDepth requestedDepth, String steeringSource) {
        StringBuilder input = new StringBuilder().append(conversationId).append('\n')
                .append(message.length()).append(':').append(message).append('\n')
                .append(contextType).append(':').append(contextId);
        if (references != null) {
            for (AgentContextReferenceRequest reference : references) {
                input.append('\n').append(reference.type()).append(':').append(reference.id());
            }
        }
        ReasoningDepth depth = ReasoningDepth.orAuto(requestedDepth);
        if (depth != ReasoningDepth.AUTO) input.append("\nreasoning:").append(depth);
        if (steeringSource != null) input.append("\ninterrupted:").append(steeringSource);
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

    private record ActiveRun(UUID userId, Instant startedAt) {}
}
