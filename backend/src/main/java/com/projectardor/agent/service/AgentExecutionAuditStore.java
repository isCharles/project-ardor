package com.projectardor.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.projectardor.agent.capability.ToolCatalog;

/**
 * Structured, owner-scoped execution facts for one durable agent run.
 *
 * <p>Rows are additive audit projections, never authorization input: the tool wrapper and
 * business services still decide what may execute. Only digests of arguments/results and a
 * bounded correlation reference are stored, never the raw payload. A write failure here is
 * logged and must not change an already authorized business result; a missing audit row
 * therefore means "not recorded", not "not executed".
 */
@Service
public class AgentExecutionAuditStore {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionAuditStore.class);
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public AgentExecutionAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The trusted owner, conversation and durable run a tool invocation belongs to. */
    public record ToolCallContext(UUID userId, UUID conversationId, UUID runId) {
        public static ToolCallContext of(UUID userId, UUID conversationId, UUID runId) {
            return userId == null || conversationId == null || runId == null
                    ? null : new ToolCallContext(userId, conversationId, runId);
        }
    }

    /** A proposal created by a tool; its key is the legacy card identity old clients still use. */
    public record ProposalRecord(UUID proposalId, ToolCallContext context, String kind, UUID targetId,
            List<UUID> targetIds, String legacyKey, String label, String detail, String endpoint,
            UUID learningRequestId) {
    }

    // ---- tool calls -------------------------------------------------------------------

    /** Records an invocation that has started (including its approval wait). Returns the row id. */
    public UUID startToolCall(ToolCallContext context, String toolCallId, String toolName, String arguments,
            String approvalSource) {
        if (context == null || context.runId() == null || context.conversationId() == null) return null;
        UUID id = UUID.randomUUID();
        ToolCatalog.Risk risk = ToolCatalog.describe(toolName);
        try {
            jdbc.update("""
                    INSERT INTO agent_tool_calls(id, user_id, conversation_id, run_id, tool_call_id, tool_name,
                        module, capability, approval_source, arguments_digest, arguments_size, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED')
                    """, id, context.userId(), context.conversationId(), context.runId(),
                    trim(toolCallId, 200), trim(toolName, 80),
                    risk == null ? null : trim(risk.module().key(), 40),
                    risk == null ? null : trim(risk.capability().name(), 16),
                    approvalSource, digest(arguments), length(arguments));
            return id;
        } catch (RuntimeException failure) {
            log.warn("Could not record agent tool call start: runId={}, tool={}, failure={}",
                    context.runId(), toolName, failure.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Finalizes one invocation. A row already marked {@code UNKNOWN} may still become
     * {@code COMPLETED}/{@code FAILED} when the fenced call really returned a result; a
     * terminal status is never overwritten with a fabricated success.
     */
    public void finishToolCall(UUID id, UUID userId, String status, String result, String errorCode) {
        if (id == null || userId == null) return;
        try {
            jdbc.update("""
                    UPDATE agent_tool_calls SET status = ?, error_code = ?, result_digest = ?,
                           result_size = ?, result_ref = ?, finished_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND user_id = ? AND status IN ('STARTED', 'UNKNOWN')
                    """, status, trim(errorCode, 80), result == null ? null : digest(result),
                    result == null ? null : length(result), resultReference(result), id, userId);
        } catch (RuntimeException failure) {
            log.warn("Could not record agent tool call result: toolCallRow={}, failure={}",
                    id, failure.getClass().getSimpleName());
        }
    }

    /**
     * Truthful terminal attribution for a cancelled/deadlined/failed run: only rows that
     * never reported a result become UNKNOWN, and still-open proposals become CANCELLED.
     * Already completed calls keep their real result.
     */
    public void finishInterruptedRun(UUID userId, UUID runId, String reasonCode) {
        if (userId == null || runId == null) return;
        try {
            jdbc.update("""
                    UPDATE agent_tool_calls SET status = 'UNKNOWN', error_code = ?,
                           finished_at = COALESCE(finished_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = ? AND run_id = ? AND status = 'STARTED'
                    """, trim(reasonCode, 80), userId, runId);
            jdbc.update("""
                    UPDATE agent_proposals SET status = 'CANCELLED',
                           resolved_at = COALESCE(resolved_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = ? AND run_id = ? AND status = 'PROPOSED'
                    """, userId, runId);
        } catch (RuntimeException failure) {
            log.warn("Could not finalize interrupted agent run audit: runId={}, failure={}",
                    runId, failure.getClass().getSimpleName());
        }
    }

    /**
     * Links the saved exchange to the run: messages receive request/run identity, tool calls
     * and proposals receive the assistant message id. Called after the messages are flushed.
     */
    public void linkRunExchange(UUID userId, UUID runId, UUID userMessageId, UUID assistantMessageId) {
        if (userId == null || runId == null) return;
        try {
            if (userMessageId != null) linkMessage(userId, runId, userMessageId);
            if (assistantMessageId != null) linkMessage(userId, runId, assistantMessageId);
            jdbc.update("""
                    UPDATE agent_tool_calls SET message_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = ? AND run_id = ? AND message_id IS NULL
                    """, assistantMessageId, userId, runId);
            jdbc.update("""
                    UPDATE agent_proposals SET message_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = ? AND run_id = ? AND message_id IS NULL
                    """, assistantMessageId, userId, runId);
        } catch (RuntimeException failure) {
            log.warn("Could not link agent run exchange: runId={}, failure={}",
                    runId, failure.getClass().getSimpleName());
        }
    }

    private void linkMessage(UUID userId, UUID runId, UUID messageId) {
        jdbc.update("""
                UPDATE messages SET request_id = ?, run_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND run_id IS NULL
                """, runId, runId, messageId, userId);
    }

    public List<ToolCall> toolCalls(UUID userId, UUID runId) {
        return jdbc.query("""
                SELECT id, run_id, message_id, tool_call_id, tool_name, module, capability,
                       approval_source, status, error_code, arguments_digest, result_digest, result_ref,
                       started_at, finished_at
                FROM agent_tool_calls
                WHERE user_id = ? AND run_id = ?
                ORDER BY started_at, id
                LIMIT 500
                """, TOOL_CALL_MAPPER, userId, runId);
    }

    private static final RowMapper<ToolCall> TOOL_CALL_MAPPER = (rs, row) -> new ToolCall(
            rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
            rs.getObject("message_id", UUID.class), rs.getString("tool_call_id"), rs.getString("tool_name"),
            rs.getString("module"), rs.getString("capability"), rs.getString("approval_source"),
            rs.getString("status"), rs.getString("error_code"), rs.getString("arguments_digest"),
            rs.getString("result_digest"), rs.getString("result_ref"),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());

    // ---- proposals --------------------------------------------------------------------

    /** Persists a proposal card as a stable entity. Duplicate generation keeps the first row. */
    public void recordProposal(ProposalRecord proposal) {
        if (proposal == null || proposal.proposalId() == null || proposal.context() == null) return;
        ToolCallContext context = proposal.context();
        try {
            jdbc.update("""
                    INSERT INTO agent_proposals(id, user_id, conversation_id, run_id, kind, target_id,
                        target_ids, legacy_key, label, detail, endpoint, learning_request_id, status)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, 'PROPOSED')
                    ON CONFLICT (id) DO NOTHING
                    """, proposal.proposalId(), context.userId(), context.conversationId(), context.runId(),
                    trim(proposal.kind(), 40), proposal.targetId(), targetIdsJson(proposal.targetIds()),
                    trim(proposal.legacyKey(), 4096), trim(proposal.label(), 240),
                    trim(proposal.detail(), 2000), trim(proposal.endpoint(), 240),
                    proposal.learningRequestId());
        } catch (RuntimeException failure) {
            log.warn("Could not record agent proposal: runId={}, kind={}, failure={}",
                    context.runId(), proposal.kind(), failure.getClass().getSimpleName());
        }
    }

    /**
     * Applies the first user decision to an open proposal row. Returns false when no row was
     * updated (already decided, or a pre-V53 message whose card only exists in the trace).
     */
    public boolean decideProposal(UUID userId, UUID messageId, UUID proposalId, String legacyKey,
            String resolution) {
        String status = proposalStatus(resolution);
        if (userId == null || status == null) return false;
        try {
            int updated = jdbc.update("""
                    UPDATE agent_proposals SET status = ?, resolution = ?, resolution_source = 'USER_DECISION',
                           resolved_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = ? AND status = 'PROPOSED'
                      AND (id = CAST(? AS uuid) OR (message_id = CAST(? AS uuid) AND legacy_key = ?))
                    """, status, resolution, userId, proposalId, messageId, legacyKey);
            return updated == 1;
        } catch (RuntimeException failure) {
            log.warn("Could not record agent proposal decision: messageId={}, failure={}",
                    messageId, failure.getClass().getSimpleName());
            return false;
        }
    }

    static String proposalStatus(String resolution) {
        return switch (resolution == null ? "" : resolution) {
            case "DONE", "ACCEPTED" -> "CONFIRMED";
            case "DISMISSED" -> "DISMISSED";
            case "EXPIRED" -> "EXPIRED";
            default -> null;
        };
    }

    public List<Proposal> proposals(UUID userId, UUID runId) {
        return jdbc.query("""
                SELECT id, run_id, message_id, kind, target_id, legacy_key, status, resolution,
                       learning_request_id, created_at, resolved_at
                FROM agent_proposals
                WHERE user_id = ? AND run_id = ?
                ORDER BY created_at, id
                LIMIT 200
                """, (rs, row) -> new Proposal(
                rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("message_id", UUID.class), rs.getString("kind"),
                rs.getObject("target_id", UUID.class), rs.getString("legacy_key"), rs.getString("status"),
                rs.getString("resolution"), rs.getObject("learning_request_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant()),
                userId, runId);
    }

    // ---- legacy audit projection ------------------------------------------------------

    /**
     * Adds a CONFIRMED / DELETE_PROPOSAL / PROPOSAL event to the pre-existing audit table.
     * Auto-approved execution keeps using {@link AgentPermissionGrants#recordAutoApproval}.
     */
    public void recordAudit(UUID userId, UUID runId, String tool, String arguments, String approvalSource) {
        if (userId == null || tool == null || approvalSource == null) return;
        ToolCatalog.Risk risk = ToolCatalog.describe(tool);
        if (risk == null) return;
        try {
            jdbc.update("""
                    INSERT INTO agent_tool_audit(id, user_id, run_id, tool, module, capability,
                        arguments_digest, approval_source)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), userId, runId, trim(tool, 80), risk.module().key(),
                    risk.capability().name(), digest(arguments == null ? "" : arguments), approvalSource);
        } catch (RuntimeException failure) {
            log.warn("Could not record agent tool audit: tool={}, failure={}", tool,
                    failure.getClass().getSimpleName());
        }
    }

    public List<AuditEvent> auditEvents(UUID userId, UUID runId) {
        return jdbc.query("""
                SELECT id, tool, module, capability, approval_source, arguments_digest, created_at
                FROM agent_tool_audit
                WHERE user_id = ? AND run_id = ?
                ORDER BY created_at, id
                LIMIT 500
                """, (rs, row) -> new AuditEvent(
                rs.getObject("id", UUID.class), rs.getString("tool"), rs.getString("module"),
                rs.getString("capability"), rs.getString("approval_source"),
                rs.getString("arguments_digest"), rs.getTimestamp("created_at").toInstant()),
                userId, runId);
    }

    // ---- records ----------------------------------------------------------------------

    public record ToolCall(UUID id, UUID runId, UUID messageId, String toolCallId, String toolName,
            String module, String capability, String approvalSource, String status, String errorCode,
            String argumentsDigest, String resultDigest, String resultRef,
            Instant startedAt, Instant finishedAt) {
    }

    public record Proposal(UUID id, UUID runId, UUID messageId, String kind, UUID targetId,
            String legacyKey, String status, String resolution, UUID learningRequestId,
            Instant createdAt, Instant resolvedAt) {
    }

    public record AuditEvent(UUID id, String tool, String module, String capability,
            String approvalSource, String argumentsDigest, Instant createdAt) {
    }

    // ---- helpers ----------------------------------------------------------------------

    static String digest(String value) {
        return AgentPermissionGrants.digest(value);
    }

    private static String targetIdsJson(List<UUID> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return null;
        return JSON.writeValueAsString(targetIds);
    }

    /**
     * Extracts one bounded correlation reference from a serialized tool result. It is only a
     * convenience for joining a created job/resource; it is never treated as proof of success
     * or as an authorization token. Free text is not mined for UUIDs.
     */
    static String resultReference(String result) {
        if (result == null || result.isBlank() || result.length() > 512_000) return null;
        try {
            return findReference(JSON.readTree(result), 0);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static String findReference(tools.jackson.databind.JsonNode node, int depth) {
        if (node == null || depth > 3) return null;
        if (node.isArray()) {
            for (var child : node) {
                String found = findReference(child, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (!node.isObject()) return null;
        for (String preferred : List.of("requestId", "jobId", "id")) {
            String found = uuidText(node.get(preferred));
            if (found != null) return found;
        }
        var fields = node.properties();
        for (var field : fields) {
            if (!field.getKey().endsWith("Id")) continue;
            String found = uuidText(field.getValue());
            if (found != null) return found;
        }
        return null;
    }

    private static String uuidText(tools.jackson.databind.JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        String value = node.asText();
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    private static Integer length(String value) {
        return value == null ? null : value.length();
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
