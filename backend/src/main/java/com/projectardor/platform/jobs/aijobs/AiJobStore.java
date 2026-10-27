package com.projectardor.platform.jobs.aijobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class AiJobStore {
    private static final RowMapper<Job> MAPPER = (rs, row) -> new Job(rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class), AiJobType.valueOf(rs.getString("job_type")),
            rs.getObject("resource_id", UUID.class), rs.getString("request_hash"), rs.getString("payload"),
            rs.getString("status"), rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getInt("priority"),
            rs.getString("result_path"), rs.getString("error_code"), rs.getString("error_message"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
            rs.getTimestamp("retry_after") == null ? null : rs.getTimestamp("retry_after").toInstant());
    private final JdbcTemplate jdbc;
    private final long staleSeconds;
    public AiJobStore(JdbcTemplate jdbc,@org.springframework.beans.factory.annotation.Value("${app.ai-jobs.stale-after:5m}") java.time.Duration staleAfter) {
        this.jdbc = jdbc;
        if (staleAfter.compareTo(java.time.Duration.ofSeconds(5))<0) throw new IllegalArgumentException("任务失联阈值不能少于 5 秒");
        this.staleSeconds=staleAfter.getSeconds();
    }

    public Job enqueue(UUID userId, UUID requestId, AiJobType type, UUID resourceId, String payload, int priority) {
        UUID id = requestId == null ? UUID.randomUUID() : requestId;
        String hash = hash(type + ":" + resourceId + ":" + payload);
        int inserted = jdbc.update("""
                INSERT INTO ai_jobs(id, user_id, job_type, resource_id, request_hash, payload, priority)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?) ON CONFLICT DO NOTHING
                """, id, userId, type.name(), resourceId, hash, payload, priority);
        if (inserted == 0) {
            var existing = jdbc.query("SELECT * FROM ai_jobs WHERE id = ? AND user_id = ?", MAPPER, id, userId);
            if (existing.isEmpty()) {
                boolean ownPending = !list(userId, type, resourceId).stream()
                        .filter(job -> "QUEUED".equals(job.status()) || "RUNNING".equals(job.status())).toList().isEmpty();
                if (ownPending) throw new IllegalStateException("该内容已有评估任务，请查看任务状态");
                throw new ResourceNotFoundException("评估任务不存在");
            }
            if (!existing.getFirst().requestHash().equals(hash)) throw new IllegalArgumentException("请求 ID 已用于另一份提交");
            return existing.getFirst();
        }
        return get(userId, id);
    }

    public Job get(UUID userId, UUID id) {
        return find(userId, id).orElseThrow(() -> new ResourceNotFoundException("评估任务不存在"));
    }

    public Optional<Job> find(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM ai_jobs WHERE id = ? AND user_id = ?", MAPPER, id, userId).stream().findFirst();
    }

    public List<Job> list(UUID userId, AiJobType type, UUID resourceId) {
        return jdbc.query("""
                SELECT * FROM ai_jobs WHERE user_id = ?
                  AND (CAST(? AS varchar) IS NULL OR job_type = ?)
                  AND (CAST(? AS uuid) IS NULL OR resource_id = ?)
                ORDER BY created_at DESC, id DESC LIMIT 30
                """, MAPPER, userId, type == null ? null : type.name(), type == null ? null : type.name(), resourceId, resourceId);
    }

    public Optional<Job> claimNext() {
        return jdbc.query("""
                WITH picked AS (SELECT id FROM ai_jobs WHERE status = 'QUEUED'
                    AND (retry_after IS NULL OR retry_after <= CURRENT_TIMESTAMP)
                    ORDER BY priority DESC, created_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE ai_jobs j SET status = 'RUNNING', attempts = attempts + 1,
                    started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, retry_after = NULL
                FROM picked WHERE j.id = picked.id RETURNING j.*
                """, MAPPER).stream().findFirst();
    }

    public boolean complete(Job job, String resultPath) {
        return jdbc.update("""
                UPDATE ai_jobs SET status = 'COMPLETED', result_path = ?, error_code = NULL,
                    error_message = NULL, finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING' AND attempts = ?
                """, resultPath, job.id(), job.userId(), job.attempts()) == 1;
    }

    public boolean fail(Job job, String code, String message) {
        return jdbc.update("""
                UPDATE ai_jobs SET status = 'FAILED', error_code = ?, error_message = ?,
                    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING' AND attempts = ?
                """, code, message.substring(0, Math.min(500, message.length())), job.id(), job.userId(), job.attempts()) == 1;
    }

    public boolean requeue(Job job, Instant retryAfter) {
        return jdbc.update("""
                UPDATE ai_jobs SET status = 'QUEUED', retry_after = ?,
                    error_code = NULL, error_message = NULL,
                    started_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING' AND attempts = ?
                """, retryAfter == null ? null : java.sql.Timestamp.from(retryAfter),
                job.id(), job.userId(), job.attempts()) == 1;
    }

    public boolean deadLetter(Job job, String code, String message) {
        return jdbc.update("""
                UPDATE ai_jobs SET status = 'DEAD', error_code = ?, error_message = ?,
                    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING' AND attempts = ?
                """, code, message.substring(0, Math.min(500, message.length())),
                job.id(), job.userId(), job.attempts()) == 1;
    }

    public Job cancel(UUID userId, UUID id) {
        Job job = get(userId, id);
        if (!"QUEUED".equals(job.status())) throw new IllegalStateException("只有排队中的任务可以取消");
        jdbc.update("""
                UPDATE ai_jobs SET status = 'CANCELED', finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'QUEUED'
                """, id, userId);
        return get(userId, id);
    }

    public Job retry(UUID userId, UUID id) {
        Job job = get(userId, id);
        if (!"FAILED".equals(job.status()) && !"DEAD".equals(job.status()))
            throw new IllegalStateException("只有失败或死信任务可以重试");
        jdbc.update("""
                UPDATE ai_jobs SET status = 'QUEUED', error_code = NULL, error_message = NULL,
                    started_at = NULL, finished_at = NULL, retry_after = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('FAILED','DEAD')
                """, id, userId);
        return get(userId, id);
    }

    public void heartbeat(Job job) {
        jdbc.update("UPDATE ai_jobs SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ? AND status = 'RUNNING' AND attempts = ?",
                job.id(), job.userId(), job.attempts());
    }

    public Recovery recoverStale() {
        Instant backoff = Instant.now().plusSeconds(Math.min(staleSeconds, 60));
        int exhausted = jdbc.update("""
                UPDATE ai_jobs SET status = 'DEAD', error_code = 'AI_JOB_RECOVERY_EXHAUSTED',
                    error_message = '任务在多次失联后停止重试，请核对原提交后手动重试',
                    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND attempts >= max_attempts
                    AND updated_at < CURRENT_TIMESTAMP - (CAST(? AS double precision) * INTERVAL '1 second')
                """, staleSeconds);
        int requeued = jdbc.update("""
                UPDATE ai_jobs SET status = 'QUEUED', retry_after = ?,
                    error_code = 'AI_JOB_INTERRUPTED',
                    error_message = '评估中断，自动排队重试',
                    started_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND attempts < max_attempts
                    AND updated_at < CURRENT_TIMESTAMP - (CAST(? AS double precision) * INTERVAL '1 second')
                """, java.sql.Timestamp.from(backoff), staleSeconds);
        return new Recovery(requeued, exhausted);
    }

    public record Recovery(int requeued, int deadLettered) {}

    public int queuePosition(Job job) {
        Integer pos = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_jobs
                WHERE status = 'QUEUED'
                  AND (retry_after IS NULL OR retry_after <= CURRENT_TIMESTAMP)
                  AND (priority > ? OR (priority = ? AND created_at < ?))
                """, Integer.class, job.priority(), job.priority(),
                java.sql.Timestamp.from(job.createdAt()));
        return pos == null ? 0 : pos;
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Job(UUID id, UUID userId, AiJobType type, UUID resourceId, String requestHash, String payload,
            String status, int attempts, int maxAttempts, int priority, String resultPath, String errorCode,
            String errorMessage, Instant createdAt, Instant startedAt, Instant finishedAt, Instant retryAfter) {}
}
