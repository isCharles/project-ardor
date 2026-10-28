package com.projectardor.assistant.execution.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable record of a multi-step plan for one agent run.
 *
 * <p>Every statement is owner-scoped: a plan id belonging to another user reads and writes nothing,
 * so a step can never be resumed or rewritten across users. Step status is the resume guard — a
 * step already VERIFIED is never executed again, even when the run that continues is a different
 * request with its own deterministic write keys.
 */
@Service
public class AgentPlanStore {

    public static final String PENDING = "PENDING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String VERIFIED = "VERIFIED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    private static final String PLAN_SELECT = """
            SELECT id, user_id, request_id, conversation_id, goal, status, revision,
                   model_call_ceiling, model_calls_used, created_at, updated_at
            FROM agent_plans WHERE id = ? AND user_id = ?
            """;
    private static final String STEP_SELECT = """
            SELECT id, step_index, intent, done_when, tools, effect, status, logical_action_id, evidence
            FROM agent_plan_steps WHERE plan_id = ? AND user_id = ? ORDER BY step_index
            """;

    private final JdbcTemplate jdbc;

    public AgentPlanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StepDraft(String intent, String doneWhen, List<String> tools, String effect) {
    }

    public record Step(UUID id, int index, String intent, String doneWhen, List<String> tools, String effect,
            String status, UUID logicalActionId, String evidence) {

        public boolean verified() {
            return VERIFIED.equals(status);
        }

        public boolean open() {
            return PENDING.equals(status) || IN_PROGRESS.equals(status);
        }
    }

    public record Plan(UUID id, UUID userId, UUID requestId, UUID conversationId, String goal, String status,
            int revision, int modelCallCeiling, int modelCallsUsed, Instant createdAt, List<Step> steps) {

        public boolean active() {
            return "PROPOSED".equals(status) || "RUNNING".equals(status);
        }

        public int verifiedSteps() {
            return (int) steps.stream().filter(Step::verified).count();
        }

        public Optional<Step> nextOpenStep() {
            return steps.stream().filter(Step::open).findFirst();
        }

        /** Only the still-open steps may grant tools; a verified step's tools are no longer in scope. */
        public List<String> openToolScope() {
            return steps.stream().filter(Step::open).flatMap(step -> step.tools().stream()).distinct().toList();
        }
    }

    @Transactional
    public UUID create(UUID userId, UUID requestId, UUID conversationId, String goal, int revision,
            int modelCallCeiling, List<StepDraft> drafts) {
        UUID planId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_plans (id, user_id, request_id, conversation_id, goal, revision, model_call_ceiling)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, planId, userId, requestId, conversationId, truncate(goal, 600), revision, modelCallCeiling);
        for (int index = 0; index < drafts.size(); index++) {
            StepDraft draft = drafts.get(index);
            jdbc.update("""
                    INSERT INTO agent_plan_steps (id, plan_id, user_id, step_index, intent, done_when, tools, effect,
                            logical_action_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), planId, userId, index, truncate(draft.intent(), 600),
                    truncate(draft.doneWhen(), 600), String.join(",", draft.tools()), draft.effect(),
                    stepActionId(planId, index));
        }
        return planId;
    }

    /**
     * A stable per-step identity so a write attempted twice for the same step is recognised as the
     * same logical action instead of a second side effect.
     */
    public static UUID stepActionId(UUID planId, int stepIndex) {
        return UUID.nameUUIDFromBytes((planId + ":step:" + stepIndex).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public Optional<Plan> find(UUID userId, UUID planId) {
        return loadOne(PLAN_SELECT + " LIMIT 1", planId, userId);
    }

    /** Latest plan proposed for this run; a replan supersedes the previous one rather than editing it. */
    public Optional<Plan> findByRequest(UUID userId, UUID requestId) {
        return loadOne("""
                SELECT id, user_id, request_id, conversation_id, goal, status, revision,
                       model_call_ceiling, model_calls_used, created_at, updated_at
                FROM agent_plans WHERE user_id = ? AND request_id = ? ORDER BY revision DESC LIMIT 1
                """, requestId, userId);
    }

    public Optional<Plan> findActiveByConversation(UUID userId, UUID conversationId) {
        return loadOne("""
                SELECT id, user_id, request_id, conversation_id, goal, status, revision,
                       model_call_ceiling, model_calls_used, created_at, updated_at
                FROM agent_plans WHERE user_id = ? AND conversation_id = ?
                  AND status IN ('PROPOSED', 'RUNNING')
                ORDER BY created_at DESC LIMIT 1
                """, conversationId, userId);
    }

    private Optional<Plan> loadOne(String sql, UUID primaryKey, UUID userId) {
        List<Plan> plans = jdbc.query(sql, (rs, rowNum) -> mapPlan(rs), primaryKey, userId);
        if (plans.isEmpty()) return Optional.empty();
        Plan header = plans.get(0);
        return Optional.of(new Plan(header.id(), header.userId(), header.requestId(), header.conversationId(),
                header.goal(), header.status(), header.revision(), header.modelCallCeiling(),
                header.modelCallsUsed(), header.createdAt(), steps(header.id(), userId)));
    }

    private Plan mapPlan(ResultSet rs) throws SQLException {
        return new Plan(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("request_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getString("goal"), rs.getString("status"), rs.getInt("revision"),
                rs.getInt("model_call_ceiling"), rs.getInt("model_calls_used"),
                rs.getTimestamp("created_at").toInstant(), List.of());
    }

    private List<Step> steps(UUID planId, UUID userId) {
        return jdbc.query(STEP_SELECT, (rs, rowNum) -> new Step(
                rs.getObject("id", UUID.class), rs.getInt("step_index"), rs.getString("intent"),
                rs.getString("done_when"), Arrays.stream(rs.getString("tools").split(",")).filter(value -> !value.isBlank()).toList(),
                rs.getString("effect"), rs.getString("status"),
                rs.getObject("logical_action_id", UUID.class), rs.getString("evidence")), planId, userId);
    }

    public int markRunning(UUID userId, UUID planId) {
        return jdbc.update("""
                UPDATE agent_plans SET status = 'RUNNING', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'PROPOSED'
                """, planId, userId);
    }

    public int startStep(UUID userId, UUID stepId) {
        return jdbc.update("""
                UPDATE agent_plan_steps SET status = 'IN_PROGRESS', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status = 'PENDING'
                """, stepId, userId);
    }

    public int verifyStep(UUID userId, UUID stepId, String evidence) {
        return jdbc.update("""
                UPDATE agent_plan_steps SET status = 'VERIFIED', evidence = ?, verified_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('PENDING', 'IN_PROGRESS')
                """, truncate(evidence, 900), stepId, userId);
    }

    public int failStep(UUID userId, UUID stepId, String evidence) {
        return jdbc.update("""
                UPDATE agent_plan_steps SET status = 'FAILED', evidence = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('PENDING', 'IN_PROGRESS')
                """, truncate(evidence, 900), stepId, userId);
    }

    /** Steps the run never reached stay PENDING; they are marked SKIPPED so a resume does not rerun them. */
    public int skipOpenSteps(UUID userId, UUID planId) {
        return jdbc.update("""
                UPDATE agent_plan_steps SET status = 'SKIPPED', updated_at = CURRENT_TIMESTAMP
                WHERE plan_id = ? AND user_id = ? AND status IN ('PENDING', 'IN_PROGRESS')
                """, planId, userId);
    }

    public int finish(UUID userId, UUID planId, String status) {
        return jdbc.update("""
                UPDATE agent_plans SET status = ?, updated_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('PROPOSED', 'RUNNING')
                """, status, planId, userId);
    }

    /** The new plan replaces the old one; a corrected goal never silently edits history. */
    public int supersede(UUID userId, UUID planId) {
        return jdbc.update("""
                UPDATE agent_plans SET status = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP,
                       finished_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND status IN ('PROPOSED', 'RUNNING')
                """, planId, userId);
    }

    /**
     * Charges one model call against the plan's ceiling atomically. Zero rows means the ceiling was
     * already reached, so the caller must stop instead of continuing to plan.
     */
    public int chargeModelCall(UUID userId, UUID planId) {
        return jdbc.update("""
                UPDATE agent_plans SET model_calls_used = model_calls_used + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND model_calls_used < model_call_ceiling
                """, planId, userId);
    }

    private static String truncate(String value, int limit) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.length() <= limit ? stripped : stripped.substring(0, limit - 1) + "…";
    }
}
