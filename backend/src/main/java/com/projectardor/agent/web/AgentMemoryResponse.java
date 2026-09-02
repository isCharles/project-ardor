package com.projectardor.agent.web;

import java.time.Instant;

public record AgentMemoryResponse(String content, Instant updatedAt) {
}
