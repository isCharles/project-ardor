package com.projectardor.assistant.execution.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.projectardor.assistant.conversation.web.AgentMessageRequest;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.platform.usage.MembershipService;
import com.projectardor.platform.usage.MembershipTier;
import tools.jackson.databind.ObjectMapper;
import com.projectardor.assistant.conversation.service.AgentConversationStore;

@Service
public class AgentMessageQueue {
    private static final Logger log = LoggerFactory.getLogger(AgentMessageQueue.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentConversationStore conversations;
    private final TransactionTemplate transactions;
    private final Function<UUID, MembershipTier> tiers;
    private final long priorityAgingSeconds;

    /** Compatibility for callers without membership context; queued rows start as free tier. */
    public AgentMessageQueue(JdbcTemplate jdbc, ObjectMapper mapper, AgentConversationStore conversations,
            PlatformTransactionManager transactionManager) {
        this(jdbc, mapper, conversations, transactionManager, ignored -> MembershipTier.FREE, 60);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentMessageQueue(JdbcTemplate jdbc, ObjectMapper mapper, AgentConversationStore conversations,
            PlatformTransactionManager transactionManager, MembershipService memberships,
            @Value("${app.agent.queue.priority-aging-seconds:60}") long priorityAgingSeconds) {
        this(jdbc, mapper, conversations, transactionManager, memberships::tier, priorityAgingSeconds);
    }

    AgentMessageQueue(JdbcTemplate jdbc, ObjectMapper mapper, AgentConversationStore conversations,
            PlatformTransactionManager transactionManager, Function<UUID, MembershipTier> tiers,
            long priorityAgingSeconds) {
        this.jdbc = jdbc; this.mapper = mapper; this.conversations = conversations;
        this.transactions = new TransactionTemplate(transactionManager);
        this.tiers = tiers;
        this.priorityAgingSeconds = Math.max(1, priorityAgingSeconds);
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
        return accept(userId, request).item();
    }

    /**
     * Accept a replacement message and then ask the caller to stop the previous run.
     *
     * <p>Acceptance (insert, dependency and priority changes) commits first. Only after that do we
     * invoke the stop callback, so fencing the previous lease can never be rolled back into a
     * RUNNING database row without an executor. If the previous run cannot be stopped, the message
     * stays QUEUED; {@link #claimNext()} already refuses to dispatch while any run is RUNNING.
     */
    public Item enqueueAndReplace(UUID userId, AgentMessageRequest request, java.util.function.Consumer<Item> interrupt) {
        Accepted accepted = transactions.execute(status -> {
            Accepted result = accept(userId, request);
            if (!result.created() || !"QUEUED".equals(result.item().status())) return result;
            steerLocked(userId, result.item().id(), true);
            return result;
        });
        if (accepted == null) throw new IllegalStateException("补充消息未能进入队列");
        if (accepted.created()) interruptAfterCommit(accepted.item(), interrupt);
        return get(userId, accepted.item().id());
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
                  -- Paid rows start one level ahead. Each wait window adds a level, so a continuous
                  -- paid stream cannot starve a free row; sequence then preserves steering order.
                  ORDER BY (q.priority + FLOOR(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - q.created_at)) / ?)) DESC,
                    q.sequence FOR UPDATE SKIP LOCKED LIMIT 1
                ) UPDATE agent_message_queue q SET status='DISPATCHING',updated_at=CURRENT_TIMESTAMP
                  FROM next WHERE q.id=next.id RETURNING q.*
                """, rows, priorityAgingSeconds).stream().findFirst();
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
    public void prioritize(UUID userId, UUID id) {
        steer(userId, id, ignored -> {});
    }
    /**
     * Legacy explicit priority request. Validation and priority allocation commit first; the
     * optional stop runs afterwards so a stop failure cannot roll the queue row back.
     */
    public void steer(UUID userId, UUID id, java.util.function.Consumer<Item> interrupt) {
        steerCommitted(userId, id, false, interrupt);
    }
    public void replace(UUID userId, UUID id, java.util.function.Consumer<Item> interrupt) {
        steerCommitted(userId, id, true, interrupt);
    }
    private void steerCommitted(UUID userId, UUID id, boolean replacePending,
            java.util.function.Consumer<Item> interrupt) {
        Item item = transactions.execute(status -> steerLocked(userId, id, replacePending));
        if (item != null) interruptAfterCommit(item, interrupt);
    }
    /** Reserve the replacement row before stopping anything. The stop is never part of this transaction. */
    private Item steerLocked(UUID userId, UUID id, boolean replacePending) {
        lockOwner(userId);
        Item item = jdbc.query("SELECT * FROM agent_message_queue WHERE id=? AND user_id=? FOR UPDATE", rows, id, userId)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("排队消息不存在"));
        if (!item.status().equals("QUEUED")) throw new IllegalStateException("这条消息已开始，请刷新查看状态");
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
        return get(userId, id);
    }
    /**
     * The queue row is already durable when this runs. A previous run that cannot be stopped keeps
     * the message QUEUED; a retried request ID never stops a later run a second time.
     */
    private void interruptAfterCommit(Item item, java.util.function.Consumer<Item> interrupt) {
        if (interrupt == null) return;
        try { interrupt.accept(item); }
        catch (RuntimeException failure) {
            log.warn("Steering stop failed; message stays queued until no run is running: id={}, failure={}",
                    item.id(), failure.getClass().getSimpleName());
        }
    }
    private Accepted accept(UUID userId, AgentMessageRequest request) {
        if (request.requestId() == null || request.conversationId() == null) throw new IllegalArgumentException("排队消息需要会话与请求标识");
        conversations.requireActive(userId, request.conversationId());
        lockOwner(userId);
        String payload = mapper.writeValueAsString(request);
        var existing = jdbc.query("SELECT * FROM agent_message_queue WHERE id = ? AND user_id = ?", rows, request.requestId(), userId);
        if (!existing.isEmpty()) {
            Boolean same = jdbc.queryForObject("SELECT jsonb_strip_nulls(payload) = jsonb_strip_nulls(CAST(? AS jsonb)) FROM agent_message_queue WHERE id = ? AND user_id = ?",
                    Boolean.class, payload, request.requestId(), userId);
            if (!Boolean.TRUE.equals(same)) throw new IllegalArgumentException("请求标识已用于另一条消息");
            return new Accepted(existing.getFirst(), false);
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
        int priority = tiers.apply(userId).paid() ? 1 : 0;
        int inserted = jdbc.update("""
                INSERT INTO agent_message_queue(id,user_id,conversation_id,payload,blocked_by,priority)
                VALUES(?,?,?,CAST(? AS jsonb),?,?) ON CONFLICT(id) DO NOTHING
                """, request.requestId(), userId, request.conversationId(), payload, predecessor, priority);
        if (inserted != 1) throw new IllegalArgumentException("请求标识不可用");
        return new Accepted(get(userId, request.requestId()), true);
    }
    private record Accepted(Item item, boolean created) {}
    public record Item(UUID id, UUID userId, String status, AgentMessageRequest request, UUID blockedBy, String errorMessage, Instant createdAt) {}
}
