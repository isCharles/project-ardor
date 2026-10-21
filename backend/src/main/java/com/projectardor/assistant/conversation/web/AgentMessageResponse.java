package com.projectardor.assistant.conversation.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.projectardor.assistant.conversation.AgentContextReference;
import com.projectardor.assistant.conversation.domain.ConversationMessage;

public record AgentMessageResponse(
        UUID id,
        UUID conversationId,
        String role,
        String content,
        List<AgentContextReference> contextReferences,
        String runTrace,
        Instant createdAt,
        UUID requestId,
        java.util.Map<String, String> proposalResolutions,
        /** Durable run that saved this message; null for legacy synchronous chat and old history. */
        UUID runId) {
    public AgentMessageResponse(UUID id, UUID conversationId, String role, String content,
            List<AgentContextReference> references, String trace, Instant createdAt, UUID requestId,
            java.util.Map<String, String> proposalResolutions) {
        this(id, conversationId, role, content, references, trace, createdAt, requestId,
                proposalResolutions, requestId);
    }
    public AgentMessageResponse(UUID id, UUID conversationId, String role, String content,
            List<AgentContextReference> references, String trace, Instant createdAt, UUID requestId) {
        this(id, conversationId, role, content, references, trace, createdAt, requestId,
                java.util.Map.of(), requestId);
    }
    public AgentMessageResponse(UUID id, UUID conversationId, String role, String content,
            List<AgentContextReference> references, String trace, Instant createdAt) {
        this(id, conversationId, role, content, references, trace, createdAt, null);
    }
    public static AgentMessageResponse from(ConversationMessage message) {
        return new AgentMessageResponse(
                message.getId(), message.getConversationId(), message.getRole(),
                message.getContent(), message.getContextReferences(), message.getRunTrace(), message.getCreatedAt(),
                message.getRequestId(),
                message.getProposalResolutions(), message.getRunId());
    }
}
