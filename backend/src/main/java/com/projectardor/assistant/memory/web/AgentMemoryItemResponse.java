package com.projectardor.assistant.memory.web;

import java.time.Instant;
import java.util.UUID;
import com.projectardor.assistant.memory.domain.AgentMemoryItem;
import com.projectardor.assistant.memory.service.AgentMemoryItemView;

public record AgentMemoryItemResponse(UUID id, String content, String kind, float confidence, int importance,
        String sourceKind, Instant validFrom, Instant validUntil, boolean valid,
        Instant createdAt, Instant updatedAt) {
    public static AgentMemoryItemResponse from(AgentMemoryItem item) {
        return from(AgentMemoryItemView.from(item));
    }
    public static AgentMemoryItemResponse from(AgentMemoryItemView item) {
        return new AgentMemoryItemResponse(item.id(), item.content(), item.kind(), item.confidence(),
                item.importance(), item.sourceKind(), item.validFrom(), item.validUntil(), item.valid(),
                item.createdAt(), item.updatedAt());
    }
}
