package com.projectardor.assistant.conversation;

import java.util.UUID;

public record AgentContext(UUID userId, UUID conversationId, String userMessage) {
}

