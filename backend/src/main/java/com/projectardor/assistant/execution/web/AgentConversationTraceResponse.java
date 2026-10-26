package com.projectardor.assistant.execution.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner-scoped, multi-turn trace for one conversation. */
public record AgentConversationTraceResponse(
        UUID conversationId,
        String title,
        List<RunTrace> runs,
        UUID nextBefore) {
    public AgentConversationTraceResponse(UUID conversationId, String title, List<RunTrace> runs) {
        this(conversationId, title, runs, null);
    }

    public record RunTrace(
            UUID runId,
            String message,
            String assistantMessage,
            String thinkingOutput,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<AgentTraceSpanResponse> spans) {
    }
}

