package com.projectardor.assistant.execution.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner-scoped, multi-turn trace for one conversation. */
public record AgentConversationTraceResponse(
        UUID conversationId,
        String title,
        List<RunTrace> runs) {

    public record RunTrace(
            UUID runId,
            String message,
            String assistantMessage,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<AgentTraceSpanResponse> spans) {
    }
}
