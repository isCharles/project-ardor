package com.projectardor.agent.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.agent.domain.Conversation;

public record AgentConversationResponse(
        UUID id,
        String title,
        boolean pinned,
        Instant createdAt,
        Instant updatedAt) {

    public static AgentConversationResponse from(Conversation conversation) {
        String title = conversation.getTitle();
        return new AgentConversationResponse(
                conversation.getId(),
                title == null || title.isBlank() ? "新对话" : title,
                conversation.isPinned(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
