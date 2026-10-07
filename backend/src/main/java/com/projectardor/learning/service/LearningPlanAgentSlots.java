package com.projectardor.learning.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.learning.domain.LearningSourceType;

/** Persisted tool-action identity shared by replacement Agent runs. */
@Component
public class LearningPlanAgentSlots {
    private final JdbcTemplate jdbc;

    public LearningPlanAgentSlots(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID resolve(UUID userId, UUID logicalActionId, UUID runId, String concept,
            String reason, LearningSourceType source, UUID sourceId, Instant scheduledAt) {
        String semantic = hash(framed(concept) + framed(source.name())
                + framed(sourceId == null ? null : sourceId.toString()));
        String signature = signature(reason, scheduledAt);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                userId + ":learning-action:" + logicalActionId + ":" + semantic);
        List<Slot> slots = jdbc.query("""
                SELECT request_id, call_signature, last_used_run_id
                FROM agent_learning_action_slots
                WHERE user_id = ? AND logical_action_id = ? AND semantic_key = ?
                ORDER BY created_at, request_id
                """, (rs, row) -> new Slot(rs.getObject("request_id", UUID.class),
                rs.getString("call_signature"), rs.getObject("last_used_run_id", UUID.class)),
                userId, logicalActionId, semantic);
        if (slots.isEmpty() && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM agent_learning_action_slots
                    WHERE user_id = ? AND logical_action_id = ? AND origin_run_id <> ?
                )
                """, Boolean.class, userId, logicalActionId, runId))) {
            throw new IllegalStateException("重试生成了不同的学习主题，无法安全判断是否重复；请先查看已创建的计划");
        }
        for (Slot slot : slots) {
            if (signature.equals(slot.signature())) return use(slot.requestId(), runId);
        }
        List<Slot> available = slots.stream().filter(slot -> !runId.equals(slot.lastUsedRunId())).toList();
        if (available.size() > 1) {
            throw new IllegalStateException("这次重试包含多个相似学习计划，无法安全判定对应项；请先查看已创建的计划");
        }
        if (available.size() == 1) return use(available.getFirst().requestId(), runId);
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_learning_action_slots
                    (request_id, user_id, logical_action_id, semantic_key, call_signature,
                     origin_run_id, last_used_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, requestId, userId, logicalActionId, semantic, signature, runId, runId);
        return requestId;
    }

    private UUID use(UUID requestId, UUID runId) {
        jdbc.update("UPDATE agent_learning_action_slots SET last_used_run_id = ? WHERE request_id = ?",
                runId, requestId);
        return requestId;
    }

    private static String framed(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    static String signature(String reason, Instant scheduledAt) {
        return hash(framed(reason) + framed(scheduledAt == null ? null : scheduledAt.toString()));
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    record Slot(UUID requestId, String signature, UUID lastUsedRunId) {}
}
