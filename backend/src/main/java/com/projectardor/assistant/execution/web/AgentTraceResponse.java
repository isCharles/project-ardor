package com.projectardor.assistant.execution.web;

import java.util.List;
import java.util.UUID;

/** Read-only, developer-facing execution trace for one owned Agent run. */
public record AgentTraceResponse(
        UUID runId,
        String status,
        long durationMs,
        Totals totals,
        List<AgentTraceSpanResponse> spans) {

    public record Totals(int llmCalls, int toolCalls, int loopRounds, long inputTokens, long outputTokens) {
    }

    public static AgentTraceResponse from(UUID runId, List<AgentTraceSpanResponse> spans) {
        var root = spans.stream().filter(span -> "RUN".equals(span.kind())).findFirst().orElse(null);
        int llmCalls = 0;
        int toolCalls = 0;
        int loopRounds = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        for (var span : spans) {
            if ("LLM".equals(span.kind())) {
                llmCalls++;
                if (Boolean.TRUE.equals(span.attributes().get("toolRound"))) loopRounds++;
                if (span.inputTokens() != null) inputTokens += span.inputTokens();
                if (span.outputTokens() != null) outputTokens += span.outputTokens();
            } else if ("TOOL".equals(span.kind())) {
                toolCalls++;
            }
        }
        return new AgentTraceResponse(runId, root == null ? "UNKNOWN" : root.status(),
                root == null || root.durationMs() == null ? 0 : root.durationMs(),
                new Totals(llmCalls, toolCalls, loopRounds, inputTokens, outputTokens), spans);
    }
}
