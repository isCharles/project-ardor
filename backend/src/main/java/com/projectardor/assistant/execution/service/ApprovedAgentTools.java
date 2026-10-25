package com.projectardor.assistant.execution.service;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.projectardor.assistant.tools.modules.AgentToolBundle;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * Execution fence for all tools. Destructive adapters only prepare UI proposals.
 *
 * <p>Every invocation of a durable run is mirrored into {@link AgentExecutionAuditStore}: the
 * authorization source is recorded (auto-allowed read/write, per-call confirmation, or a card-only
 * proposal), and the outcome is recorded truthfully (COMPLETED, FAILED, NOT_EXECUTED, PROPOSED,
 * or UNKNOWN if the run ends before the call returns). Audit writes never grant or deny an action.
 */
final class ApprovedAgentTools {
    private static final Logger log = LoggerFactory.getLogger(ApprovedAgentTools.class);
    // These adapters ONLY construct existing UI confirmation proposals. They never mutate business data.
    private static final Set<String> PROPOSALS = Set.of("create_learning_plan", "delete_learning_plan",
            "delete_knowledge_document", "delete_memory", "clear_all_memories", "delete_calendar_task",
            "delete_recurring_task", "delete_resume", "delete_interview", "delete_memory_card",
            "delete_all_memory_cards", "delete_interview_recap", "delete_job_opportunity",
            "delete_job_opportunity_round", "delete_automation", "delete_calendar_advice");
    /** Advisory writes that a trusted scheduled automation may persist without a live approval dialog. */
    private static final Set<String> AUTOMATION_WRITES = Set.of("update_calendar_advice", "update_application_advice");
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals) {
        return wrap(bound, userId, requestId, approvals, null, new AgentToolTimings(), waiting -> {}, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, String userRequest) {
        return wrap(bound, userId, requestId, approvals, null, new AgentToolTimings(), waiting -> {}, userRequest);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease) {
        return wrap(bound, userId, requestId, approvals, lease, new AgentToolTimings(), waiting -> {}, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings) {
        return wrap(bound, userId, requestId, approvals, lease, timings, waiting -> {}, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings, Consumer<Boolean> waiting) {
        return wrap(bound, userId, requestId, approvals, lease, timings, waiting, null, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, String userRequest, AgentPermissionGrants permissions) {
        return wrap(bound, userId, requestId, approvals, null, new AgentToolTimings(), waiting -> {}, userRequest, permissions);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings, Consumer<Boolean> waiting, String userRequest) {
        return wrap(bound, userId, requestId, approvals, lease, timings, waiting, userRequest, null);
    }
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId, AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings, Consumer<Boolean> waiting, String userRequest, AgentPermissionGrants permissions) {
        return wrap(bound, userId, requestId, approvals, lease, timings, waiting, userRequest, permissions, null, null, false);
    }

    /** Trusted scheduled automation may persist its own low-risk advice cards without a live user. */
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId,
            AgentToolApprovals approvals, String userRequest, AgentPermissionGrants permissions, boolean automation) {
        return wrap(bound, userId, requestId, approvals, null, new AgentToolTimings(), waiting -> {},
                userRequest, permissions, null, null, automation, null);
    }

    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId,
            AgentToolApprovals approvals, String userRequest, AgentPermissionGrants permissions, boolean automation,
            AgentRunTracer tracer) {
        return wrap(bound, userId, requestId, approvals, null, new AgentToolTimings(), waiting -> {},
                userRequest, permissions, null, null, automation, tracer);
    }

    /** Full variant used by a durable streaming run: adds the structured execution audit context. */
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId,
            AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings, Consumer<Boolean> waiting,
            String userRequest, AgentPermissionGrants permissions, AgentExecutionAuditStore executionAudit,
            UUID conversationId) {
        return wrap(bound, userId, requestId, approvals, lease, timings, waiting, userRequest, permissions,
                executionAudit, conversationId, false, null);
    }

    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId,
            AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings, Consumer<Boolean> waiting,
            String userRequest, AgentPermissionGrants permissions, AgentExecutionAuditStore executionAudit,
            UUID conversationId, boolean automation) {
        return wrap(bound, userId, requestId, approvals, lease, timings, waiting, userRequest, permissions,
                executionAudit, conversationId, automation, null);
    }

    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId,
            AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings, Consumer<Boolean> waiting,
            String userRequest, AgentPermissionGrants permissions, AgentExecutionAuditStore executionAudit,
            UUID conversationId, boolean automation, AgentRunTracer tracer) {
        return wrap(bound, userId, requestId, approvals, lease, timings, waiting, userRequest, permissions,
                executionAudit, conversationId, automation, tracer, null);
    }

    /** A graph phase may only see an explicit read-only subset of the tool surface. */
    static Map<ToolSpecification, ToolExecutor> wrap(Object bound, UUID userId, UUID requestId,
            AgentToolApprovals approvals, AgentStreamLease lease, AgentToolTimings timings, Consumer<Boolean> waiting,
            String userRequest, AgentPermissionGrants permissions, AgentExecutionAuditStore executionAudit,
            UUID conversationId, boolean automation, AgentRunTracer tracer, Set<String> allowedTools) {
        Map<ToolSpecification, ToolExecutor> result = new LinkedHashMap<>();
        Map<String, Boolean> attemptedWrites = new java.util.concurrent.ConcurrentHashMap<>();
        var automaticWriteOrdinal = new java.util.concurrent.atomic.AtomicInteger();
        AgentExecutionAuditStore.ToolCallContext auditContext =
                AgentExecutionAuditStore.ToolCallContext.of(userId, conversationId, requestId);
        Set<String> names = new HashSet<>();
        for (Object toolObject : AgentToolBundle.objects(bound)) {
        for (var method : toolObject.getClass().getMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) continue;
            if (allowedTools != null && !allowedTools.contains(method.getAnnotation(Tool.class).name())) continue;
            var specification = ToolSpecifications.toolSpecificationFrom(method);
            if (!names.add(specification.name())) throw new IllegalStateException("工具重复登记：" + specification.name());
            var delegate = new DefaultToolExecutor(toolObject, method);
            result.put(specification, (request, memoryId) -> {
                if (!specification.name().equals(request.name())) throw new IllegalArgumentException("工具名称不匹配");
                String tool = specification.name();
                boolean destructive = tool.startsWith("delete_") || tool.equals("clear_all_memories");
                boolean proposal = PROPOSALS.contains(tool);
                boolean read = ToolIntentPolicy.isRead(tool);
                boolean automationWrite = automation && AUTOMATION_WRITES.contains(tool);
                boolean actualWrite = !read && !proposal;
                String writeKey = actualWrite ? tool + "\u0000" + request.arguments() : null;
                long requested = timings.now();
                if (actualWrite && bound instanceof ToolIntentScope intentScope && !intentScope.permitsWrite(tool)) {
                    // The user's trusted wording is captured before model output. A write that is
                    // outside that scope must never reach auto-approval or the business service.
                    UUID blockedRow = startAudit(executionAudit, auditContext, request, tool, "AUTO");
                    finishAudit(executionAudit, blockedRow, userId, "NOT_EXECUTED", null,
                            "TOOL_INTENT_NOT_PERMITTED");
                    traceNotExecuted(tracer, tool, "TOOL_INTENT_NOT_PERMITTED");
                    timings.record(request.id(), requested, requested, false);
                    return "{\"status\":\"TOOL_NOT_PERMITTED\",\"message\":\"当前用户请求没有授权这次写入；未执行本次操作，不要重复调用或声称已完成。\"}";
                }
                boolean allowed = (!destructive || proposal) && (proposal || read || requestId != null || automationWrite)
                        && (read ? approvals.canRead(userId, requestId)
                                : automationWrite || approvals.canPropose(userId, requestId));
                boolean autoApproved = false;
                if (allowed && !proposal && !read && !automationWrite && permissions != null
                        && permissions.autoApproves(userId, tool)
                        && !permissions.requiresConfirmationForBatch(userId, automaticWriteOrdinal.incrementAndGet())) {
                    autoApproved = true;
                    // A permission level skips the dialog, never the audit trail.
                    permissions.recordAutoApproval(userId, requestId, tool, request.arguments());
                }
                String approvalSource = proposal ? (destructive ? "DELETE_PROPOSAL" : "PROPOSAL")
                        : read || autoApproved || automationWrite ? "AUTO" : "CONFIRMED";
                UUID auditRow = null;
                if (allowed && !autoApproved && !proposal && !read && !automationWrite) {
                    // Record the attempt before waiting so a long approval is observable as STARTED.
                    auditRow = startAudit(executionAudit, auditContext, request, tool, approvalSource);
                    waiting.accept(true);
                    try {
                        allowed = approvals.await(userId, requestId, tool, request.arguments())
                                && approvals.canPropose(userId, requestId);
                    } finally { waiting.accept(false); }
                    if (allowed) {
                        recordLegacyAudit(executionAudit, userId, requestId, tool, request.arguments(), "CONFIRMED");
                    }
                }
                long approved = timings.now();
                if (!allowed) {
                    finishAudit(executionAudit, auditRow, userId, "NOT_EXECUTED", null, "NOT_CONFIRMED");
                    traceNotExecuted(tracer, tool, "NOT_CONFIRMED");
                    timings.record(request.id(), requested, approved, false);
                    return "{\"status\":\"PERMISSION_NOT_GRANTED\",\"message\":\"用户未确认、已拒绝、确认已过期或执行已停止。未执行本次操作，不要重复请求或声称已完成。\"}";
                }
                if (actualWrite && attemptedWrites.putIfAbsent(writeKey, Boolean.TRUE) != null) {
                    // A model retry after a write failure could duplicate a side effect that was
                    // already partially committed. Reads may be retried; identical writes within
                    // one run are blocked until the model verifies the current state.
                    finishAudit(executionAudit, auditRow, userId, "NOT_EXECUTED", null,
                            "DUPLICATE_WRITE_BLOCKED");
                    if (tracer != null) {
                        UUID blockedSpan = tracer.startChild("TOOL", tool,
                                Map.of("tool", tool, "duplicateWrite", true));
                        tracer.recordTool();
                        if (blockedSpan != null) tracer.finishChild(blockedSpan, "NOT_EXECUTED",
                                "DUPLICATE_WRITE_BLOCKED", null, null, null, Map.of("retryBlocked", true));
                    }
                    timings.record(request.id(), requested, approved, false);
                    return "{\"status\":\"DUPLICATE_WRITE_BLOCKED\",\"retryable\":false,\"message\":\"本轮已经尝试过相同的写入。为避免重复副作用，本次没有再次执行；请先读取当前状态并向用户说明需要核验，不要声称操作成功。\"}";
                }
                if (auditRow == null) {
                    auditRow = startAudit(executionAudit, auditContext, request, tool, approvalSource);
                }
                UUID row = auditRow;
                Map<String, Object> toolAttributes = new LinkedHashMap<>();
                toolAttributes.put("tool", tool);
                toolAttributes.put("approvalSource", approvalSource);
                toolAttributes.put("proposal", proposal);
                toolAttributes.put("argumentsPreview", TracePreview.of(request.arguments()));
                UUID traceSpan = tracer == null ? null : tracer.startChild("TOOL", tool, toolAttributes);
                if (tracer != null) tracer.recordTool();
                AtomicBoolean executed = new AtomicBoolean();
                Supplier<String> call = () -> { executed.set(true); return delegate.execute(request, memoryId); };
                String outcome = "COMPLETED";
                String errorCode = null;
                String auditResult = null;
                String returned = null;
                try {
                    returned = lease == null ? call.get() : lease.execute(call);
                    if (!executed.get()) {
                        // The lease refused a late call after cancel/deadline: it never ran.
                        outcome = "NOT_EXECUTED";
                        errorCode = "RUN_STOPPED";
                    } else if (proposal) {
                        // The adapter only prepared a confirmation card; no business mutation happened.
                        outcome = "PROPOSED";
                        recordLegacyAudit(executionAudit, userId, requestId, tool, request.arguments(), approvalSource);
                    } else {
                        auditResult = returned;
                    }
                    return returned;
                } catch (RuntimeException failure) {
                    outcome = "FAILED";
                    errorCode = "TOOL_FAILED";
                    throw failure;
                } finally {
                    finishAudit(executionAudit, row, userId, outcome, auditResult, errorCode);
                    timings.record(request.id(), requested, approved, executed.get());
                    if (tracer != null && traceSpan != null) {
                        String spanStatus = "FAILED".equals(outcome) ? "FAILED"
                                : "NOT_EXECUTED".equals(outcome) ? "NOT_EXECUTED" : "COMPLETED";
                        Map<String, Object> resultAttributes = new LinkedHashMap<>();
                        resultAttributes.put("executed", executed.get());
                        resultAttributes.put("proposal", proposal);
                        resultAttributes.put("approvalSource", approvalSource);
                        resultAttributes.put("resultPreview", TracePreview.of(auditResult != null ? auditResult : returned));
                        tracer.finishChild(traceSpan, spanStatus, errorCode, null, null, null, resultAttributes);
                    }
                }
            });
        }
        }
        return result;
    }

    private static void traceNotExecuted(AgentRunTracer tracer, String tool, String reason) {
        if (tracer == null) return;
        UUID span = tracer.startChild("TOOL", tool, Map.of("tool", tool));
        tracer.recordTool();
        if (span != null) tracer.finishChild(span, "NOT_EXECUTED", reason, null, null, null,
                Map.of("executed", false));
    }

    private static UUID startAudit(AgentExecutionAuditStore auditStore,
            AgentExecutionAuditStore.ToolCallContext context, ToolExecutionRequest request,
            String tool, String approvalSource) {
        if (auditStore == null || context == null) return null;
        try {
            return auditStore.startToolCall(context, request.id(), tool, request.arguments(), approvalSource);
        } catch (RuntimeException failure) {
            log.warn("Agent tool audit start failed without changing authorization: tool={}, failure={}",
                    tool, failure.getClass().getSimpleName());
            return null;
        }
    }

    private static void finishAudit(AgentExecutionAuditStore auditStore, UUID row, UUID userId, String status,
            String result, String errorCode) {
        if (auditStore == null || row == null) return;
        try {
            auditStore.finishToolCall(row, userId, status, result, errorCode);
        } catch (RuntimeException failure) {
            log.warn("Agent tool audit finish failed without changing the business result: failure={}",
                    failure.getClass().getSimpleName());
        }
    }

    private static void recordLegacyAudit(AgentExecutionAuditStore auditStore, UUID userId, UUID requestId,
            String tool, String arguments, String approvalSource) {
        if (auditStore == null || userId == null) return;
        try {
            auditStore.recordAudit(userId, requestId, tool, arguments, approvalSource);
        } catch (RuntimeException failure) {
            log.warn("Agent tool legacy audit failed without changing authorization: tool={}, failure={}",
                    tool, failure.getClass().getSimpleName());
        }
    }
}
