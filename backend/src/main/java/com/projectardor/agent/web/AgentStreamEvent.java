package com.projectardor.agent.web;

import java.util.UUID;

import com.projectardor.agent.tools.CareerAgentTools.PendingConfirmation;

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
        AgentConfirmation confirmation) {

    /** What the confirmation button says, and the path it calls when pressed. */
    public record AgentConfirmation(
            String kind, UUID targetId, String label, String detail, String endpoint) {

        public static AgentConfirmation from(PendingConfirmation pending) {
            return new AgentConfirmation(pending.kind(), pending.targetId(),
                    pending.label(), pending.detail(), pending.endpoint());
        }
    }

    public static AgentStreamEvent confirm(PendingConfirmation pending) {
        return new AgentStreamEvent("confirm", null, null, "等待确认", null, null, null, null, null,
                AgentConfirmation.from(pending));
    }

    public static AgentStreamEvent status(String label, long elapsedMs) {
        return new AgentStreamEvent("status", null, null, label, elapsedMs, null, null, null, null, null);
    }
    public static AgentStreamEvent delta(String content) {
        return new AgentStreamEvent("delta", content, null, null, null, null, null, null, null, null);
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
