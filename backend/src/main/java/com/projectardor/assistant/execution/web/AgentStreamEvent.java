package com.projectardor.assistant.execution.web;

import java.util.UUID;

import com.projectardor.assistant.tools.CareerAgentTools.PendingConfirmation;
import com.projectardor.learning.plan.service.LearningCreationCommand;
import com.projectardor.assistant.conversation.web.AgentMessageResponse;

public record AgentStreamEvent(
        String type,
        String content,
        String toolName,
        String label,
        Long elapsedMs,
        UUID conversationId,
        UUID messageId,
        Boolean retryable,
        String errorCode,
        /** Set on "confirm" events: a deletion waiting for the user to press a button. */
        AgentConfirmation confirmation, Long approvalWaitMs, Long businessMs, String toolStatus,
        com.projectardor.assistant.execution.service.AgentContextUsage contextUsage) {

    public AgentStreamEvent(String type, String content, String toolName, String label, Long elapsedMs,
            UUID conversationId, UUID messageId, Boolean retryable, String errorCode, AgentConfirmation confirmation,
            Long approvalWaitMs, Long businessMs, String toolStatus) {
        this(type, content, toolName, label, elapsedMs, conversationId, messageId, retryable, errorCode,
                confirmation, approvalWaitMs, businessMs, toolStatus, null);
    }

    public static AgentStreamEvent context(com.projectardor.assistant.execution.service.AgentContextUsage usage) {
        return new AgentStreamEvent("context", null, null, null, null, null, null, null, null, null, null, null, null, usage);
    }

    public AgentStreamEvent(String type, String content, String toolName, String label, Long elapsedMs,
            UUID conversationId, UUID messageId, Boolean retryable, String errorCode, AgentConfirmation confirmation) {
        this(type, content, toolName, label, elapsedMs, conversationId, messageId, retryable, errorCode, confirmation, null, null, null);
    }
    public static AgentStreamEvent toolFinished(String name, String label, long elapsedMs, Long approvalWaitMs, Long businessMs, String status) {
        return new AgentStreamEvent("tool_end", null, name, label, elapsedMs, null, null, null, null, null, approvalWaitMs, businessMs, status);
    }

    /** What the confirmation button says, and the authenticated action it offers. */
    public record AgentConfirmation(
            String kind, UUID targetId, String label, String detail, String endpoint,
            LearningCreationCommand learningPlan, java.util.List<UUID> targetIds,
            /** Stable per-run proposal identity; presentation/audit correlation only. */
            UUID proposalId) {

        public AgentConfirmation(String kind, UUID targetId, String label, String detail, String endpoint,
                LearningCreationCommand learningPlan, java.util.List<UUID> targetIds) {
            this(kind, targetId, label, detail, endpoint, learningPlan, targetIds, null);
        }

        public static AgentConfirmation from(PendingConfirmation pending) {
            return new AgentConfirmation(pending.kind(), pending.targetId(),
                    pending.label(), pending.detail(), pending.endpoint(), pending.learningPlan(),
                    pending.targetIds(), pending.proposalId());
        }
    }

    public static AgentStreamEvent confirm(PendingConfirmation pending) {
        return new AgentStreamEvent("confirm", null, null, "等待确认", null, null, null, null, null,
                AgentConfirmation.from(pending));
    }

    public static AgentStreamEvent status(String label, long elapsedMs) {
        return new AgentStreamEvent("status", null, null, label, elapsedMs, null, null, null, null, null);
    }
    public static AgentStreamEvent phase(String phase, String label, long elapsedMs) {
        return new AgentStreamEvent("phase", phase, null, label, elapsedMs, null, null, null, null, null);
    }
    public static AgentStreamEvent delta(String content) {
        return new AgentStreamEvent("delta", content, null, null, null, null, null, null, null, null);
    }
    public static AgentStreamEvent intermediate(String content) {
        return new AgentStreamEvent("intermediate", content, null, null, null, null, null, null, null, null);
    }
    public static AgentStreamEvent phaseFinished(String phase, String label, long elapsedMs, String status) {
        return new AgentStreamEvent("phase_end", phase, null, label, elapsedMs, null, null, null, null, null,
                null, null, status);
    }
    public static AgentStreamEvent thinking(String content) {
        return new AgentStreamEvent("thinking", content, null, "收到模型思考输出", null, null, null, null, null, null);
    }
    public static AgentStreamEvent tool(String type, String name, String label, long elapsedMs) {
        return new AgentStreamEvent(type, null, name, label, elapsedMs, null, null, null, null, null);
    }
    public static AgentStreamEvent done(AgentMessageResponse response, long elapsedMs) {
        return new AgentStreamEvent("done", response.content(), null, "完成", elapsedMs,
                response.conversationId(), response.id(), null, null, null);
    }
    public static AgentStreamEvent error(String code, String message, boolean retryable, long elapsedMs) {
        return new AgentStreamEvent("error", message, null, "失败", elapsedMs, null, null, retryable, code, null);
    }
}
