package com.projectardor.agent.web;

import java.util.List;
import java.util.UUID;

public record AgentStateResponse(
        UUID conversationId,
        boolean llmConfigured,
        String displayName,
        List<AgentMessageResponse> messages,
        List<AgentConversationResponse> conversations,
        List<AgentConversationResponse> archivedConversations,
        AgentMemoryResponse memory) {
}
