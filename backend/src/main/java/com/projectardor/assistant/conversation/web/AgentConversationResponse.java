package com.projectardor.assistant.conversation.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.assistant.conversation.domain.Conversation;

public record AgentConversationResponse(
        UUID id,
        String title,
        boolean pinned,
        boolean titleManuallySet,
        Instant createdAt,
        Instant updatedAt) {

    public static AgentConversationResponse from(Conversation conversation) {
        String title = conversation.getTitle();
        return new AgentConversationResponse(
                conversation.getId(),
                title == null || title.isBlank() ? "新对话" : title,
                conversation.isPinned(),
                conversation.isTitleManuallySet(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
