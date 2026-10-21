package com.projectardor.assistant.execution.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Snapshots are owned by the same durable run. They confer no permission to resume work. */
@Service
public class AgentProgressStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AgentProgressStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public void save(UUID owner, UUID runId, AgentStreamProgress.Snapshot snapshot) {
        String json = mapper.writeValueAsString(snapshot);
        jdbc.update("""
                UPDATE agent_runs SET progress_snapshot=?::jsonb
                WHERE id=? AND user_id=?
                  AND COALESCE((progress_snapshot->>'revision')::bigint,-1) < ?
                """, json, runId, owner, snapshot.revision());
    }

    public Optional<Saved> find(UUID owner, UUID runId) {
        return jdbc.query("""
                SELECT progress_snapshot::text, status, created_at, finished_at FROM agent_runs
                WHERE id=? AND user_id=? AND progress_snapshot IS NOT NULL
                """, (rs, row) -> {
                    var snapshot = mapper.readValue(rs.getString(1), AgentStreamProgress.Snapshot.class);
                    var finished = rs.getTimestamp(4);
                    long elapsed = finished == null ? snapshot.view().elapsedMs() : Math.max(0,
                            java.time.Duration.between(rs.getTimestamp(3).toInstant(), finished.toInstant()).toMillis());
                    return new Saved(snapshot, rs.getString(2), elapsed);
                },
                runId, owner).stream().findFirst();
    }
    public record Saved(AgentStreamProgress.Snapshot snapshot, String status, long elapsedMs) {
        Saved(AgentStreamProgress.Snapshot snapshot, String status) { this(snapshot, status, snapshot.view().elapsedMs()); }
    }
}
