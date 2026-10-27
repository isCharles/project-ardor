package com.projectardor.scheduling.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.scheduling.domain.CalendarTaskPriority;

/**
 * Persisted semantic identity for Agent-created calendar tasks.
 * The raw JSON argument string is not an identity: whitespace or property order
 * can change between a committed write and a lost response.
 */
@Component
public class CalendarTaskAgentSlots {
    private final JdbcTemplate jdbc;

    public CalendarTaskAgentSlots(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<UUID> existing(UUID userId, UUID logicalActionId, UUID runId, String semanticKey) {
        if (logicalActionId == null || runId == null) return Optional.empty();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                userId + ":calendar-action:" + logicalActionId + ":" + semanticKey);
        List<Slot> slots = jdbc.query("""
                SELECT task_id, semantic_key, origin_run_id
                FROM agent_calendar_create_slots
                WHERE user_id = ? AND logical_action_id = ?
                ORDER BY created_at, task_id
                """, (rs, row) -> new Slot(rs.getObject("task_id", UUID.class),
                rs.getString("semantic_key"), rs.getObject("origin_run_id", UUID.class)),
                userId, logicalActionId);
        for (Slot slot : slots) {
            if (semanticKey.equals(slot.semanticKey())) return Optional.of(slot.taskId());
        }
        if (!slots.isEmpty() && slots.stream().anyMatch(slot -> !runId.equals(slot.originRunId()))) {
            throw new IllegalStateException("重试生成了不同的日程，无法安全判断是否重复；请先查看已创建的日程");
        }
        return Optional.empty();
    }

    @Transactional
    public void record(UUID userId, UUID logicalActionId, UUID runId, String semanticKey,
            String callSignature, UUID taskId) {
        if (logicalActionId == null || runId == null) return;
        jdbc.update("""
                INSERT INTO agent_calendar_create_slots
                    (request_id, user_id, logical_action_id, semantic_key, call_signature,
                     origin_run_id, task_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, logicalActionId, semanticKey, callSignature, runId, taskId);
    }

    static String semanticKey(String title, Instant dueAt, CalendarTaskPriority priority, UUID jobOpportunityId) {
        return hash(framed(normalize(title)) + framed(dueAt == null ? null : dueAt.toString())
                + framed(priority == null ? CalendarTaskPriority.MEDIUM.name() : priority.name())
                + framed(jobOpportunityId == null ? null : jobOpportunityId.toString()));
    }

    static String callSignature(String description) {
        return hash(framed(normalize(description)));
    }

    private static String normalize(String value) {
        return value == null ? null : value.strip().replaceAll("\\s+", " ");
    }

    private static String framed(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    record Slot(UUID taskId, String semanticKey, UUID originRunId) {}
}
