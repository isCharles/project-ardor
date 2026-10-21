package com.projectardor.assistant.execution.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.assistant.execution.service.AgentRunStore.AgentRun;

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
        Instant updatedAt,
        /** Assistant message saved for a completed run; null until the exchange is committed. */
        UUID assistantMessageId) {

    /** Compatibility constructor for callers/tests that predate the message link. */
    public AgentRunResponse(UUID id, UUID conversationId, String message, String status, String label,
            String errorCode, String errorMessage, boolean retryable, Instant createdAt, Instant updatedAt) {
        this(id, conversationId, message, status, label, errorCode, errorMessage, retryable,
                createdAt, updatedAt, null);
    }

    public static AgentRunResponse from(AgentRun run) {
        return new AgentRunResponse(run.id(), run.conversationId(), run.message(), run.status(),
                run.label(), run.errorCode(), run.errorMessage(), run.retryable(),
                run.createdAt(), run.updatedAt(), run.assistantMessageId());
    }
}
