package com.projectardor.agent.web;

import java.time.Instant;
import java.util.UUID;
import com.projectardor.agent.domain.AgentMemoryItem;

public record AgentMemoryItemResponse(UUID id, String content, Instant createdAt) {
    public static AgentMemoryItemResponse from(AgentMemoryItem item) {
        return new AgentMemoryItemResponse(item.getId(), item.getContent(), item.getCreatedAt());
    }
}
