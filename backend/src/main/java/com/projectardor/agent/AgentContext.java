package com.projectardor.agent;

import java.util.UUID;

public record AgentContext(UUID userId, UUID conversationId, String userMessage) {
}

