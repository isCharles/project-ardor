package com.projectardor.assistant.execution.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only, developer-facing execution trace for one owned Agent run.
 *
 * <p>{@code spans} is one page; {@code truncated}/{@code nextOffset} describe the rest. Totals are
 * read from the RUN summary written at completion so a paged response still reports full-run call
 * counts. When that summary is missing, totals are derived from the spans and marked incomplete
 * whenever the page does not cover the run.</p>
 */
public record AgentTraceResponse(
        UUID runId,
        UUID conversationId,
        String status,
        long durationMs,
        Totals totals,
        List<AgentTraceSpanResponse> spans,
        int returnedSpans,
        int totalSpans,
        Integer nextOffset,
        boolean truncated) {

    public record Totals(int llmCalls, int assistantCalls, int summaryCalls, int toolCalls,
            int requestedToolCalls, int executedToolCalls, int loopRounds,
            long inputTokens, long outputTokens, int usageMissingCalls,
            String source, boolean complete) {
    }

    public static AgentTraceResponse from(UUID runId, UUID conversationId, List<AgentTraceSpanResponse> spans) {
        return from(runId, conversationId, null, spans, spans.size(), null);
    }

    public static AgentTraceResponse from(UUID runId, UUID conversationId, AgentTraceSpanResponse root,
            List<AgentTraceSpanResponse> spans, int totalSpans, Integer nextOffset) {
        boolean truncated = nextOffset != null;
        var page = derive(spans);
        var runRoot = root != null ? root
                : spans.stream().filter(span -> "RUN".equals(span.kind())).findFirst().orElse(null);
        Map<String, Object> runAttributes = runRoot == null || runRoot.attributes() == null
                ? Map.of() : runRoot.attributes();
        var totals = runAttributes.get("llmCalls") instanceof Number
                ? fromRunSummary(runRoot, runAttributes, page, truncated)
                : fromSpans(page, truncated);
        return new AgentTraceResponse(runId, conversationId,
                runRoot == null ? "UNKNOWN" : runRoot.status(),
                runRoot == null || runRoot.durationMs() == null ? 0 : runRoot.durationMs(),
                totals, spans, spans.size(), totalSpans, nextOffset, truncated);
    }

    private static Totals fromRunSummary(AgentTraceSpanResponse root, Map<String, Object> attributes,
            Derived page, boolean truncated) {
        return new Totals(
                intAttr(attributes, "llmCalls", page.llmCalls),
                optionalIntAttr(attributes, "assistantCalls", truncated, page.assistantCalls),
                optionalIntAttr(attributes, "summaryCalls", truncated, page.summaryCalls),
                intAttr(attributes, "toolCalls", page.toolCalls),
                optionalIntAttr(attributes, "requestedToolCalls", truncated, page.requestedToolCalls),
                optionalIntAttr(attributes, "executedToolCalls", truncated, page.executedToolCalls),
                intAttr(attributes, "loopRounds", page.loopRounds),
                root.inputTokens() == null ? 0 : root.inputTokens(),
                root.outputTokens() == null ? 0 : root.outputTokens(),
                optionalIntAttr(attributes, "usageMissingCalls", truncated, page.usageMissingCalls),
                "RUN_SUMMARY", true);
    }

    private static Totals fromSpans(Derived page, boolean truncated) {
        return new Totals(page.llmCalls, page.assistantCalls, page.summaryCalls, page.toolCalls,
                page.requestedToolCalls, page.executedToolCalls, page.loopRounds,
                page.inputTokens, page.outputTokens, page.usageMissingCalls,
                truncated ? "SPANS_PARTIAL" : "SPANS", !truncated);
    }

    private static Derived derive(List<AgentTraceSpanResponse> spans) {
        int llmCalls = 0;
        int assistantCalls = 0;
        int summaryCalls = 0;
        int toolCalls = 0;
        int requestedToolCalls = 0;
        int executedToolCalls = 0;
        int loopRounds = 0;
        int usageMissingCalls = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        for (var span : spans) {
            Map<String, Object> attributes = span.attributes() == null ? Map.of() : span.attributes();
            if ("LLM".equals(span.kind())) {
                llmCalls++;
                if ("CONTEXT_COMPACTION".equals(attributes.get("purpose"))) summaryCalls++;
                else assistantCalls++;
                if (Boolean.TRUE.equals(attributes.get("toolRound"))) loopRounds++;
                if (attributes.get("requestedTools") instanceof List<?> requested) {
                    requestedToolCalls += requested.size();
                }
                if (span.inputTokens() == null || span.outputTokens() == null) usageMissingCalls++;
                if (span.inputTokens() != null) inputTokens += span.inputTokens();
                if (span.outputTokens() != null) outputTokens += span.outputTokens();
            } else if ("TOOL".equals(span.kind())) {
                toolCalls++;
                if (Boolean.TRUE.equals(attributes.get("executed"))) executedToolCalls++;
            }
        }
        return new Derived(llmCalls, assistantCalls, summaryCalls, toolCalls, requestedToolCalls,
                executedToolCalls, loopRounds, inputTokens, outputTokens, usageMissingCalls);
    }

    private static int intAttr(Map<String, Object> attributes, String key, int fallback) {
        return attributes.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    /** -1 means the page cannot supply this counter and the run summary did not record it. */
    private static int optionalIntAttr(Map<String, Object> attributes, String key, boolean truncated, int fallback) {
        return attributes.get(key) instanceof Number number ? number.intValue()
                : truncated ? -1 : fallback;
    }

    private record Derived(int llmCalls, int assistantCalls, int summaryCalls, int toolCalls,
            int requestedToolCalls, int executedToolCalls, int loopRounds,
            long inputTokens, long outputTokens, int usageMissingCalls) {
    }
}
