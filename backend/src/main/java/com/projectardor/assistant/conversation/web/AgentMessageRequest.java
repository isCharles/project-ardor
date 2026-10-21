package com.projectardor.assistant.conversation.web;

import java.util.UUID;
import java.util.List;

import com.projectardor.platform.usage.QuotaRequestId;
import com.projectardor.assistant.ReasoningDepth;

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
        UUID requestId,
        ReasoningDepth reasoningDepth,
        UUID interruptedRunId) implements QuotaRequestId {

    public AgentMessageRequest(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> contextReferences, UUID requestId, ReasoningDepth reasoningDepth) {
        this(conversationId, message, contextType, contextId, contextReferences, requestId, reasoningDepth, null);
    }

    public AgentMessageRequest(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> contextReferences, UUID requestId) {
        this(conversationId, message, contextType, contextId, contextReferences, requestId, ReasoningDepth.AUTO);
    }

    public AgentMessageRequest(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> contextReferences) {
        this(conversationId, message, contextType, contextId, contextReferences, null, ReasoningDepth.AUTO);
    }
}
