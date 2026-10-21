package com.projectardor.assistant.memory.web;

import java.time.Instant;

public record AgentMemoryResponse(String content, Instant updatedAt) {
}
