package com.projectardor.platform.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class BackgroundTaskStore {
    private final JdbcTemplate jdbc;
    public BackgroundTaskStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String SELECT = """
        SELECT c.*, (c.status IN ('COMPLETED','FAILED','DEAD','CANCELED') AND NOT EXISTS (
            SELECT 1 FROM background_task_reads r WHERE r.user_id=c.user_id
              AND r.kind=c.kind AND r.task_id=c.id AND r.completion_token=c.completion_token)) AS unread
        FROM background_task_catalog c WHERE c.user_id=?
        """;
    private static Instant time(java.sql.ResultSet row, String name) throws java.sql.SQLException {
        var timestamp = row.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static final RowMapper<Task> MAPPER = (rs, index) -> new Task(rs.getObject("id", UUID.class),
            rs.getString("kind"), rs.getString("module"), rs.getString("title"), rs.getString("status"),
            rs.getInt("attempts"), time(rs,"created_at"), time(rs,"started_at"), time(rs,"finished_at"),
            rs.getString("error_code"), "KNOWLEDGE_INDEX".equals(rs.getString("kind")) ? "/app/knowledge?document="+rs.getObject("resource_id",UUID.class) : rs.getString("result_path"), rs.getObject("resource_id", UUID.class),
            rs.getString("completion_token"), rs.getBoolean("unread"), 30, 120);

    public List<Task> list(UUID userId, int offset, int limit,String kind,UUID resourceId) {
        return jdbc.query(SELECT + " AND (CAST(? AS varchar) IS NULL OR c.kind=?) AND (CAST(? AS uuid) IS NULL OR c.resource_id=?) ORDER BY c.created_at DESC, c.id DESC, c.kind LIMIT ? OFFSET ?", MAPPER,
                userId,kind,kind,resourceId,resourceId,limit,offset);
    }
    public Task get(UUID userId, String kind, UUID id) {
        return jdbc.query(SELECT + " AND c.kind=? AND c.id=?", MAPPER, userId, kind, id).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("后台任务不存在"));
    }
    public Summary summary(UUID userId) {
        Map<String, Long> unread = new LinkedHashMap<>();
        long active = 0;
        var rows = jdbc.queryForList("""
                SELECT module, COUNT(*) FILTER (WHERE status IN ('QUEUED','RUNNING')) AS active,
                COUNT(*) FILTER (WHERE status IN ('COMPLETED','FAILED','DEAD','CANCELED') AND NOT EXISTS (
                    SELECT 1 FROM background_task_reads r WHERE r.user_id=c.user_id AND r.kind=c.kind
                    AND r.task_id=c.id AND r.completion_token=c.completion_token)) AS unread
                FROM background_task_catalog c WHERE user_id=? GROUP BY module
                """, userId);
        for (var row : rows) {
            unread.put((String)row.get("module"), ((Number)row.get("unread")).longValue());
            active += ((Number)row.get("active")).longValue();
        }
        return new Summary(unread, active);
    }
    public void read(UUID userId, String kind, UUID id, String token) {
        var task = get(userId, kind, id);
        if (!List.of("COMPLETED","FAILED","DEAD","CANCELED").contains(task.status()) || !task.completionToken().equals(token))
            throw new IllegalStateException("任务状态已更新，请刷新后查看");
        if (!task.unread()) return;
        int changed = jdbc.update("""
                INSERT INTO background_task_reads(user_id,kind,task_id,completion_token)
                SELECT user_id,kind,id,completion_token FROM background_task_catalog
                WHERE user_id=? AND kind=? AND id=? AND completion_token=? AND status IN ('COMPLETED','FAILED','DEAD','CANCELED')
                ON CONFLICT(user_id,kind,task_id,completion_token) DO NOTHING
                """, userId, kind, id, token);
        if (changed == 0) {
            var current = get(userId,kind,id);
            if (current.completionToken().equals(token) && !current.unread()) return;
            throw new IllegalStateException("任务状态已更新，请刷新后查看");
        }
    }
    public void readModule(UUID userId, String module) {
        jdbc.update("""
                INSERT INTO background_task_reads(user_id,kind,task_id,completion_token)
                SELECT user_id,kind,id,completion_token FROM background_task_catalog
                WHERE user_id=? AND module=? AND status IN ('COMPLETED','FAILED','DEAD','CANCELED')
                ON CONFLICT(user_id,kind,task_id,completion_token) DO NOTHING
                """, userId, module);
    }
    public record Summary(Map<String, Long> unreadByModule, long activeCount) {}
    public record Task(UUID id, String kind, String module, String title, String status, int attempts,
            Instant createdAt, Instant startedAt, Instant finishedAt, String errorCode, String resultPath,
            UUID resourceId, String completionToken, boolean unread, int estimateMinSeconds, int estimateMaxSeconds) {}
}
