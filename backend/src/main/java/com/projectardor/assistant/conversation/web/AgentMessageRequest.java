package com.projectardor.assistant.conversation.web;

import java.util.UUID;
import java.util.List;

import com.projectardor.platform.usage.QuotaRequestId;
import com.projectardor.assistant.ReasoningDepth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public record AgentMessageRequest(
        UUID conversationId,
        @Size(max = 50000, message = "消息不能超过 50000 个字符")
        String message,
        String contextType,
        UUID contextId,
        @Size(max = 5, message = "一次最多引用 5 份资料")
        List<AgentContextReferenceRequest> contextReferences,
        UUID requestId,
        ReasoningDepth reasoningDepth,
        @Size(max = 24, message = "模型选项无效")
        String modelChoice,
        @Size(max = 4, message = "一次最多附带 4 张图片")
        List<UUID> imageIds,
        UUID interruptedRunId) implements QuotaRequestId {

    public AgentMessageRequest {
        imageIds = imageIds == null ? List.of() : List.copyOf(imageIds);
    }

    @AssertTrue(message = "消息或图片不能为空")
    public boolean hasContent() {
        return message != null && !message.isBlank() || !imageIds.isEmpty();
    }

    public AgentMessageRequest(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> contextReferences, UUID requestId, ReasoningDepth reasoningDepth) {
        this(conversationId, message, contextType, contextId, contextReferences, requestId, reasoningDepth, null, List.of(), null);
    }

    public AgentMessageRequest(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> contextReferences, UUID requestId) {
        this(conversationId, message, contextType, contextId, contextReferences, requestId, ReasoningDepth.AUTO);
    }

    public AgentMessageRequest(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> contextReferences, UUID requestId, ReasoningDepth reasoningDepth,
            String modelChoice, UUID interruptedRunId) {
        this(conversationId, message, contextType, contextId, contextReferences, requestId, reasoningDepth,
                modelChoice, List.of(), interruptedRunId);
    }

    public AgentMessageRequest(UUID conversationId, String message, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> contextReferences) {
        this(conversationId, message, contextType, contextId, contextReferences, null, ReasoningDepth.AUTO);
    }
}
