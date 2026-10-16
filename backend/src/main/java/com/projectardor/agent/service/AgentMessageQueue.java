package com.projectardor.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.agent.web.AgentMessageRequest;
import com.projectardor.common.web.ResourceNotFoundException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AgentMessageQueue {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentConversationStore conversations;
    public AgentMessageQueue(JdbcTemplate jdbc, ObjectMapper mapper, AgentConversationStore conversations) {
        this.jdbc = jdbc; this.mapper = mapper; this.conversations = conversations;
    }
    private final RowMapper<Item> rows = this::mapRow;
    private Item mapRow(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new Item(rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class), rs.getString("status"),
            mapper.readValue(rs.getString("payload"), AgentMessageRequest.class),
            rs.getObject("blocked_by", UUID.class), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant());
    }

    @Transactional
    public Item enqueue(UUID userId, AgentMessageRequest request) {
        if (request.requestId() == null || request.conversationId() == null) throw new IllegalArgumentException("排队消息需要会话与请求标识");
        conversations.requireActive(userId, request.conversationId());
        lockOwner(userId);
        String payload = mapper.writeValueAsString(request);
        var existing = jdbc.query("SELECT * FROM agent_message_queue WHERE id = ? AND user_id = ?", rows, request.requestId(), userId);
        if (!existing.isEmpty()) {
            Boolean same = jdbc.queryForObject("SELECT jsonb_strip_nulls(payload) = jsonb_strip_nulls(CAST(? AS jsonb)) FROM agent_message_queue WHERE id = ? AND user_id = ?",
                    Boolean.class, payload, request.requestId(), userId);
            if (!Boolean.TRUE.equals(same)) throw new IllegalArgumentException("请求标识已用于另一条消息");
            return existing.getFirst();
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_runs WHERE id=?)", Boolean.class, request.requestId()))) {
            throw new IllegalArgumentException("请求标识不可用");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_message_queue WHERE user_id = ? AND status IN ('QUEUED','DISPATCHING','RUNNING')", Integer.class, userId);
        if (count != null && count >= 20) throw new IllegalStateException("队列最多保留 20 条待处理消息");
        // A queued predecessor need not have begun an agent run yet. Its ID is immutable.
        var previous = jdbc.query("""
                SELECT * FROM agent_message_queue WHERE user_id = ? AND status <> 'CANCELLED'
                ORDER BY sequence DESC LIMIT 1
                """, rows, userId).stream().findFirst().orElse(null);
        UUID activeRun = jdbc.queryForList("""
                SELECT id FROM agent_runs WHERE user_id = ? AND status = 'RUNNING'
                ORDER BY created_at DESC LIMIT 1
                """, UUID.class, userId).stream().findFirst().orElse(null);
        UUID predecessor = previous == null || previous.status().equals("COMPLETED") ? activeRun
                : List.of("FAILED", "INTERRUPTED").contains(previous.status()) && activeRun != null ? activeRun : previous.id();
        int inserted = jdbc.update("""
                INSERT INTO agent_message_queue(id,user_id,conversation_id,payload,blocked_by)
                VALUES(?,?,?,CAST(? AS jsonb),?) ON CONFLICT(id) DO NOTHING
                """, request.requestId(), userId, request.conversationId(), payload, predecessor);
        if (inserted != 1) throw new IllegalArgumentException("请求标识不可用");
        return get(userId, request.requestId());
    }
    public List<Item> list(UUID userId, UUID conversationId) {
        conversations.requireActive(userId, conversationId);
        return jdbc.query("SELECT * FROM agent_message_queue WHERE user_id = ? AND conversation_id = ? ORDER BY created_at DESC,sequence DESC LIMIT 50", rows, userId, conversationId)
                .reversed();
    }
    public Item get(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM agent_message_queue WHERE id = ? AND user_id = ?", rows, id, userId)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("排队消息不存在"));
    }
    @Transactional
    public Optional<Item> claimNext() {
        return jdbc.query("""
                WITH next AS (
                  SELECT q.id FROM agent_message_queue q WHERE q.status = 'QUEUED'
                    AND (q.blocked_by IS NULL OR EXISTS (
                      SELECT 1 FROM agent_runs r WHERE r.id=q.blocked_by AND r.user_id=q.user_id AND r.status='COMPLETED'))
                    AND NOT EXISTS (SELECT 1 FROM agent_message_queue p WHERE p.user_id=q.user_id
                      AND (p.status IN ('DISPATCHING','RUNNING') OR (p.status='QUEUED' AND p.sequence<q.sequence)))
                    AND NOT EXISTS (SELECT 1 FROM agent_runs r WHERE r.user_id=q.user_id AND r.status='RUNNING')
                  ORDER BY q.sequence FOR UPDATE SKIP LOCKED LIMIT 1
                ) UPDATE agent_message_queue q SET status='DISPATCHING',updated_at=CURRENT_TIMESTAMP
                  FROM next WHERE q.id=next.id RETURNING q.*
                """, rows).stream().findFirst();
    }
    public void reconcile() {
        jdbc.update("""
                UPDATE agent_message_queue q SET status=r.status,
                  error_message=LEFT(r.error_message,400),updated_at=CURRENT_TIMESTAMP
                FROM agent_runs r WHERE q.id=r.id AND q.user_id=r.user_id
                  AND q.status IN ('DISPATCHING','RUNNING') AND r.status IN ('RUNNING','COMPLETED','FAILED','INTERRUPTED')
                """);
        // Unknown dispatch after a process crash is NOT safe to replay automatically.
        jdbc.update("""
                UPDATE agent_message_queue q SET status='INTERRUPTED',error_message='执行状态待确认，请先检查操作结果',updated_at=CURRENT_TIMESTAMP
                WHERE q.status='DISPATCHING' AND q.updated_at<CURRENT_TIMESTAMP-INTERVAL '2 minutes'
                  AND NOT EXISTS(SELECT 1 FROM agent_runs r WHERE r.id=q.id AND r.user_id=q.user_id)
                """);
    }
    public void notStarted(Item item) {
        jdbc.update("UPDATE agent_message_queue SET status='QUEUED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status='DISPATCHING'", item.id(), item.userId());
    }
    public void interrupted(Item item) {
        jdbc.update("UPDATE agent_message_queue SET status='INTERRUPTED',error_message='执行状态待确认，请先检查操作结果',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status IN ('DISPATCHING','RUNNING')", item.id(), item.userId());
    }
    public void failedBeforeStart(Item item) {
        jdbc.update("UPDATE agent_message_queue SET status='FAILED',error_message='消息未能开始，请检查引用资料和模型配置',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status='DISPATCHING'", item.id(), item.userId());
    }
    @Transactional
    public void cancel(UUID userId, UUID id) {
        lockOwner(userId);
        Item item = get(userId, id);
        if (!item.status().equals("QUEUED")) throw new IllegalStateException("只能移除尚未开始的排队消息");
        int updated = jdbc.update("UPDATE agent_message_queue SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status='QUEUED'", id, userId);
        if (updated != 1) throw new IllegalStateException("消息已经开始，不能移除");
        jdbc.update("UPDATE agent_message_queue SET blocked_by=? WHERE user_id=? AND blocked_by=? AND status='QUEUED'", item.blockedBy(), userId, id);
    }
    @Transactional
    public void resume(UUID userId, UUID id) {
        lockOwner(userId);
        Item item = get(userId, id);
        if (!item.status().equals("QUEUED")) throw new IllegalStateException("消息不在等待队列中");
        if (item.blockedBy() == null || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_runs WHERE id=? AND user_id=? AND status IN ('FAILED','INTERRUPTED'))
                  OR EXISTS(SELECT 1 FROM agent_message_queue WHERE id=? AND user_id=? AND status IN ('FAILED','INTERRUPTED','CANCELLED'))
                """, Boolean.class, item.blockedBy(), userId, item.blockedBy(), userId))) {
            throw new IllegalStateException("上一条消息尚未失败或中断，无需手动继续");
        }
        // Explicit user acknowledgement releases only this predecessor dependency, not an active run.
        jdbc.update("UPDATE agent_message_queue SET blocked_by=NULL WHERE id=? AND user_id=? AND status='QUEUED'", id, userId);
    }
    private void lockOwner(UUID userId) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, userId).isEmpty()) throw new ResourceNotFoundException("用户不存在");
    }
    @Transactional
    public void prioritize(UUID userId, UUID id) {
        steer(userId, id, ignored -> {});
    }
    @Transactional
    public void steer(UUID userId, UUID id, java.util.function.Consumer<Item> interrupt) {
        steerLocked(userId, id, interrupt, false);
    }
    @Transactional
    public void replace(UUID userId, UUID id, java.util.function.Consumer<Item> interrupt) {
        steerLocked(userId, id, interrupt, true);
    }
    /** Keep acceptance and replacement under the same owner lock and transaction. */
    @Transactional
    public Item enqueueAndReplace(UUID userId, AgentMessageRequest request, java.util.function.Consumer<Item> interrupt) {
        lockOwner(userId);
        Item item = enqueue(userId, request);
        if (!"QUEUED".equals(item.status())) return item;
        steerLocked(userId, item.id(), interrupt, true);
        return get(userId, item.id());
    }
    private void steerLocked(UUID userId, UUID id, java.util.function.Consumer<Item> interrupt, boolean replacePending) {
        lockOwner(userId);
        Item item = jdbc.query("SELECT * FROM agent_message_queue WHERE id=? AND user_id=? FOR UPDATE", rows, id, userId)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("排队消息不存在"));
        if (!item.status().equals("QUEUED")) throw new IllegalStateException("这条消息已开始，请刷新查看状态");
        // Reserve the replacement before stopping anything. Cancel/removal and the worker
        // cannot consume this row between validation and prioritization.
        interrupt.accept(item);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_runs WHERE user_id=? AND status='RUNNING')", Boolean.class, userId))) {
            throw new IllegalStateException("当前执行仍在停止，请稍后重试；没有重放旧操作");
        }
        // Serialize priority allocation across owners because sequence is globally unique.
        // Every explicit choice gets a lower slot, so the latest choice wins.
        jdbc.execute("SELECT pg_advisory_xact_lock(714073811)");
        Long priority = jdbc.queryForObject("SELECT LEAST(0,COALESCE(MIN(sequence),0))-1 FROM agent_message_queue", Long.class);
        int changed = jdbc.update("""
                UPDATE agent_message_queue SET sequence=?,blocked_by=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND user_id=? AND status='QUEUED'
                """, priority, id, userId);
        if (changed != 1) throw new IllegalStateException("这条消息已经开始，未重复派发");
        if (replacePending) jdbc.update("""
                WITH superseded AS (
                  UPDATE agent_message_queue SET status='CANCELLED',error_message='已由后续补充替代，未执行',updated_at=CURRENT_TIMESTAMP
                  WHERE user_id=? AND conversation_id=? AND id<>? AND status='QUEUED' RETURNING id
                ) UPDATE agent_message_queue SET blocked_by=?
                  WHERE user_id=? AND status='QUEUED' AND blocked_by IN (SELECT id FROM superseded)
                """, userId, item.request().conversationId(), id, id, userId);
    }
    public record Item(UUID id, UUID userId, String status, AgentMessageRequest request, UUID blockedBy, String errorMessage, Instant createdAt) {}
}
