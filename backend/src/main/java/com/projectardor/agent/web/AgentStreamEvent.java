package com.projectardor.agent.web;

import java.util.UUID;

public record AgentStreamEvent(
        String type,
        String content,
        String toolName,
        String label,
        Long elapsedMs,
        UUID conversationId,
        UUID messageId,
        Boolean retryable,
        String errorCode) {

    public static AgentStreamEvent status(String label, long elapsedMs) {
        return new AgentStreamEvent("status", null, null, label, elapsedMs, null, null, null, null);
    }
    public static AgentStreamEvent delta(String content) {
        return new AgentStreamEvent("delta", content, null, null, null, null, null, null, null);
    }
    public static AgentStreamEvent tool(String type, String name, String label, long elapsedMs) {
        return new AgentStreamEvent(type, null, name, label, elapsedMs, null, null, null, null);
    }
    public static AgentStreamEvent done(AgentMessageResponse response, long elapsedMs) {
        return new AgentStreamEvent("done", response.content(), null, "完成", elapsedMs,
                response.conversationId(), response.id(), null, null);
    }
    public static AgentStreamEvent error(String code, String message, boolean retryable, long elapsedMs) {
        return new AgentStreamEvent("error", message, null, "失败", elapsedMs, null, null, retryable, code);
    }
}
