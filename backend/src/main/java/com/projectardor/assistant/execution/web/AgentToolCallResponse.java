package com.projectardor.assistant.execution.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.assistant.execution.service.AgentExecutionAuditStore;

/** One tool invocation fact for an owned run. Arguments/results are digests, never raw payloads. */
public record AgentToolCallResponse(
        UUID id,
        UUID runId,
        UUID messageId,
        String toolCallId,
        String toolName,
        String module,
        String capability,
        String approvalSource,
        String status,
        String errorCode,
        String argumentsDigest,
        String resultDigest,
        String resultRef,
        Instant startedAt,
        Instant finishedAt) {

    public static AgentToolCallResponse from(AgentExecutionAuditStore.ToolCall call) {
        return new AgentToolCallResponse(call.id(), call.runId(), call.messageId(), call.toolCallId(),
                call.toolName(), call.module(), call.capability(), call.approvalSource(), call.status(),
                call.errorCode(), call.argumentsDigest(), call.resultDigest(), call.resultRef(),
                call.startedAt(), call.finishedAt());
    }
}
