package com.projectardor.agent.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.agent.domain.ConversationMessage;

public record AgentMessageResponse(
        UUID id,
        UUID conversationId,
        String role,
        String content,
        String runTrace,
        Instant createdAt) {
    public static AgentMessageResponse from(ConversationMessage message) {
        return new AgentMessageResponse(
                message.getId(), message.getConversationId(), message.getRole(),
                message.getContent(), message.getRunTrace(), message.getCreatedAt());
    }
}
