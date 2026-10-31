package com.projectardor.assistant.execution.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.projectardor.assistant.execution.service.AgentTraceStore;

/** One owner-scoped trace span. Attributes are bounded metadata, never raw prompts or payloads. */
public record AgentTraceSpanResponse(
        UUID id,
        UUID runId,
        UUID parentSpanId,
        String kind,
        String name,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String model,
        String promptVersion,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheCreationInputTokens,
        Integer cacheReadInputTokens,
        String errorCode,
        Map<String, Object> attributes,
        String otelTraceId,
        String otelSpanId) {

    public static AgentTraceSpanResponse from(AgentTraceStore.Span span) {
        return new AgentTraceSpanResponse(span.id(), span.runId(), span.parentSpanId(), span.kind(), span.name(),
                span.status(), span.startedAt(), span.finishedAt(), span.durationMs(), span.model(),
                span.promptVersion(), span.inputTokens(), span.outputTokens(),
                span.cacheCreationInputTokens(), span.cacheReadInputTokens(),
                span.errorCode(), span.attributes(), span.otelTraceId(), span.otelSpanId());
    }
}
