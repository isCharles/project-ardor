package com.projectardor.agent.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.agent.service.AgentRunStore.AgentRun;

public record AgentRunResponse(
        UUID id,
        UUID conversationId,
        String message,
        String status,
        String label,
        String errorCode,
        String errorMessage,
        boolean retryable,
        Instant createdAt,
        Instant updatedAt) {

    public static AgentRunResponse from(AgentRun run) {
        return new AgentRunResponse(run.id(), run.conversationId(), run.message(), run.status(),
                run.label(), run.errorCode(), run.errorMessage(), run.retryable(),
                run.createdAt(), run.updatedAt());
    }
}
