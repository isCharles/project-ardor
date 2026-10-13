package com.projectardor.aijobs;

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
            rs.getString("status"), rs.getInt("attempts"), rs.getString("result_path"),
            rs.getString("error_code"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    private final JdbcTemplate jdbc;
    private final long staleSeconds;
    public AiJobStore(JdbcTemplate jdbc,@org.springframework.beans.factory.annotation.Value("${app.ai-jobs.stale-after:5m}") java.time.Duration staleAfter) {
        this.jdbc = jdbc;
        if (staleAfter.compareTo(java.time.Duration.ofSeconds(5))<0) throw new IllegalArgumentException("任务失联阈值不能少于 5 秒");
        this.staleSeconds=staleAfter.getSeconds();
    }

    public Job enqueue(UUID userId, UUID requestId, AiJobType type, UUID resourceId, String payload) {
        UUID id = requestId == null ? UUID.randomUUID() : requestId;
        String hash = hash(type + ":" + resourceId + ":" + payload);
        int inserted = jdbc.update("""
                INSERT INTO ai_jobs(id, user_id, job_type, resource_id, request_hash, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING
                """, id, userId, type.name(), resourceId, hash, payload);
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
                    ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE ai_jobs j SET status = 'RUNNING', attempts = attempts + 1,
                    started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                FROM picked WHERE j.id = picked.id RETURNING j.*
                """, MAPPER).stream().findFirst();
    }

    public void complete(Job job, String resultPath) {
        jdbc.update("""
                UPDATE ai_jobs SET status = 'COMPLETED', result_path = ?, error_code = NULL,
                    error_message = NULL, finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, resultPath, job.id(), job.userId());
    }

    public void fail(Job job, String code, String message) {
        jdbc.update("""
                UPDATE ai_jobs SET status = 'FAILED', error_code = ?, error_message = ?,
                    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, code, message.substring(0, Math.min(500, message.length())), job.id(), job.userId());
    }

    public Job retry(UUID userId, UUID id) {
        Job job = get(userId, id);
        if (!"FAILED".equals(job.status())) throw new IllegalStateException("只有失败任务可以重试");
        jdbc.update("""
                UPDATE ai_jobs SET status = 'QUEUED', error_code = NULL, error_message = NULL,
                    started_at = NULL, finished_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'FAILED'
                """, id, userId);
        return get(userId, id);
    }

    public void heartbeat(Job job) {
        jdbc.update("UPDATE ai_jobs SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ? AND status = 'RUNNING'", job.id(), job.userId());
    }

    public void recoverStale() {
        jdbc.update("""
                UPDATE ai_jobs SET status = 'FAILED', error_code = 'AI_JOB_INTERRUPTED',
                    error_message = '评估中断，原提交已保留，可重试', finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND updated_at < CURRENT_TIMESTAMP - (CAST(? AS double precision) * INTERVAL '1 second')
                """,staleSeconds);
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Job(UUID id, UUID userId, AiJobType type, UUID resourceId, String requestHash, String payload,
            String status, int attempts, String resultPath, String errorCode, String errorMessage,
            Instant createdAt, Instant startedAt, Instant finishedAt) {}
}
