package com.projectardor.agent.web;

import java.util.UUID;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentMessageRequest(
        UUID conversationId,
        @NotBlank(message = "消息不能为空")
        @Size(max = 50000, message = "消息不能超过 50000 个字符")
        String message,
        String contextType,
        UUID contextId,
        @Size(max = 5, message = "一次最多引用 5 份资料")
        List<AgentContextReferenceRequest> contextReferences,
        UUID requestId) {

    public AgentMessageRequest(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> contextReferences) {
        this(conversationId, message, contextType, contextId, contextReferences, null);
    }
}
