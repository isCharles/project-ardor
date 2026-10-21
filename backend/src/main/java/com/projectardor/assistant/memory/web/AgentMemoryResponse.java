package com.projectardor.assistant.memory.web;

import java.time.Instant;
import com.projectardor.assistant.memory.service.AgentMemoryView;

public record AgentMemoryResponse(String content, Instant updatedAt) {
    public static AgentMemoryResponse from(AgentMemoryView view) {
        return new AgentMemoryResponse(view.content(), view.updatedAt());
    }
}
