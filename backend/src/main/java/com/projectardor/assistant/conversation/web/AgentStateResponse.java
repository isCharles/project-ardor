package com.projectardor.assistant.conversation.web;

import java.util.List;
import java.util.UUID;
import com.projectardor.assistant.memory.service.AgentMemoryView;

public record AgentStateResponse(
        UUID conversationId,
        boolean llmConfigured,
        String displayName,
        List<AgentMessageResponse> messages,
        List<AgentConversationResponse> conversations,
        List<AgentConversationResponse> archivedConversations,
        AgentMemoryView memory) {
}
