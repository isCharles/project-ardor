package com.projectardor.assistant.conversation.web;

import java.util.List;
import java.util.UUID;
import com.projectardor.assistant.memory.web.AgentMemoryResponse;

public record AgentStateResponse(
        UUID conversationId,
        boolean llmConfigured,
        String displayName,
        List<AgentMessageResponse> messages,
        List<AgentConversationResponse> conversations,
        List<AgentConversationResponse> archivedConversations,
        AgentMemoryResponse memory) {
}
