package com.projectardor.agent.web;

import java.time.Instant;
import java.util.UUID;
import com.projectardor.agent.domain.AgentMemoryItem;

public record AgentMemoryItemResponse(UUID id, String content, String kind, float confidence, int importance,
        String sourceKind, Instant validFrom, Instant validUntil, boolean valid,
        Instant createdAt, Instant updatedAt) {
    public static AgentMemoryItemResponse from(AgentMemoryItem item) {
        return new AgentMemoryItemResponse(item.getId(), item.getContent(), item.getKind(), item.getConfidence(),
                item.getImportance(), item.getSourceKind(), item.getValidFrom(), item.getValidUntil(), item.isValid(),
                item.getCreatedAt(), item.getUpdatedAt());
    }
}
