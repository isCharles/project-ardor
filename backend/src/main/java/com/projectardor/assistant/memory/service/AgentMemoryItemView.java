package com.projectardor.assistant.memory.service;

import java.time.Instant;
import java.util.UUID;
import com.projectardor.assistant.memory.domain.AgentMemoryItem;

/** Immutable memory application result, including validity and source metadata. */
public record AgentMemoryItemView(UUID id, String content, String kind, float confidence, int importance,
        String sourceKind, Instant validFrom, Instant validUntil, boolean valid,
        Instant createdAt, Instant updatedAt) {
    public static AgentMemoryItemView from(AgentMemoryItem item) {
        return new AgentMemoryItemView(item.getId(), item.getContent(), item.getKind(), item.getConfidence(),
                item.getImportance(), item.getSourceKind(), item.getValidFrom(), item.getValidUntil(), item.isValid(),
                item.getCreatedAt(), item.getUpdatedAt());
    }
}
