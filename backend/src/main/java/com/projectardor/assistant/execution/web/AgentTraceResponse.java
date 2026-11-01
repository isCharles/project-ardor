package com.projectardor.assistant.execution.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectardor.assistant.execution.service.AgentRunStore.RunFacts;


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
        boolean truncated,
        String traceId,
        String recordState) {

    public record Totals(int llmCalls, int assistantCalls, int summaryCalls, int toolCalls,
            int requestedToolCalls, int executedToolCalls, int loopRounds,
            long inputTokens, long outputTokens,
            Long cacheCreationInputTokens, Long cacheReadInputTokens,
            int cacheUsageMissingCalls, int usageMissingCalls,
            String source, boolean complete) {
    }

    public static AgentTraceResponse from(UUID runId, UUID conversationId, AgentTraceSpanResponse root,
            List<AgentTraceSpanResponse> spans, int totalSpans, Integer nextOffset) {
        return from(runId, conversationId, root, spans, totalSpans, nextOffset, null);
    }

    /**
     * {@code recordState} separates a trace that exists from a run whose spans were never recorded or are
     * still on their way: {@code RECORDED}, {@code PREPARING} or {@code NOT_RECORDED}. Absent spans are never
     * evidence that a step did not run, so the caller states which of the two it is looking at.
     *
     * <p>{@code facts} is what the run row itself proves. It is only consulted when no RUN span exists:
     * a run that completed with tracing off, or whose projection aged out, still has its status, its
     * duration and its call and token counts, and showing those as unknown and zero would contradict
     * the sentence underneath them. Anything the run row does not carry stays unknown (-1) and the
     * totals say so through {@code complete}.</p>
     */
    public static AgentTraceResponse from(UUID runId, UUID conversationId, AgentTraceSpanResponse root,
            List<AgentTraceSpanResponse> spans, int totalSpans, Integer nextOffset,
            RunFacts facts) {
        boolean truncated = nextOffset != null;
        var page = derive(spans);
        var runRoot = root != null ? root
                : spans.stream().filter(span -> "RUN".equals(span.kind())).findFirst().orElse(null);
        Map<String, Object> runAttributes = runRoot == null || runRoot.attributes() == null
                ? Map.of() : runRoot.attributes();
        var totals = runRoot != null ? runAttributes.get("llmCalls") instanceof Number
                ? fromRunSummary(runRoot, runAttributes, page, truncated)
                : fromSpans(page, truncated)
                : fromRunRecord(facts, page);
        String traceId = runRoot != null ? runRoot.otelTraceId()
                : spans.stream().map(AgentTraceSpanResponse::otelTraceId)
                        .filter(id -> id != null && !id.isBlank()).findFirst().orElse(null);
        Long duration = runRoot != null ? runRoot.durationMs()
                : facts == null || facts.finishedAt() == null || facts.startedAt() == null
                        ? null : Math.max(0, facts.finishedAt().toEpochMilli() - facts.startedAt().toEpochMilli());
        return new AgentTraceResponse(runId, conversationId,
                runRoot != null ? runRoot.status() : facts == null ? "UNKNOWN" : facts.status(),
                duration == null ? 0 : duration,
                totals, spans, spans.size(), totalSpans, nextOffset, truncated, traceId,
                recordState(runRoot, spans, facts));
    }

    /** Absent spans say nothing about execution, so the caller states which of the three cases it is in. */
    private static String recordState(AgentTraceSpanResponse root, List<AgentTraceSpanResponse> spans,
            RunFacts facts) {
        if (root != null || !spans.isEmpty()) return "RECORDED";
        if (facts == null) return "NOT_RECORDED";
        return "RUNNING".equals(facts.status()) ? "PREPARING" : "NOT_RECORDED";
    }

    private static Totals fromRunRecord(RunFacts facts, Derived page) {
        if (facts == null) return fromSpans(page, false);
        // The run row keeps the totals the tracer counted. Which of those calls were assistant turns,
        // which were summaries, and what the cache did only ever exists in the spans, so they stay
        // unknown here instead of being reported as none.
        return new Totals(facts.llmCalls(), -1, -1, facts.toolCalls(), -1, -1,
                facts.loopRounds(), longOrZero(facts.inputTokens()), longOrZero(facts.outputTokens()),
                facts.cacheCreationInputTokens(), facts.cacheReadInputTokens(), -1, -1,
                "RUN_RECORD", false);
    }

    private static long longOrZero(Long value) {
        return value == null ? 0 : value;
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
                root.cacheCreationInputTokens() == null ? null : root.cacheCreationInputTokens().longValue(),
                root.cacheReadInputTokens() == null ? null : root.cacheReadInputTokens().longValue(),
                optionalIntAttr(attributes, "cacheUsageMissingCalls", truncated, page.cacheUsageMissingCalls),
                optionalIntAttr(attributes, "usageMissingCalls", truncated, page.usageMissingCalls),
                "RUN_SUMMARY", true);
    }

    private static Totals fromSpans(Derived page, boolean truncated) {
        return new Totals(page.llmCalls, page.assistantCalls, page.summaryCalls, page.toolCalls,
                page.requestedToolCalls, page.executedToolCalls, page.loopRounds,
                page.inputTokens, page.outputTokens, page.cacheCreationInputTokens, page.cacheReadInputTokens,
                page.cacheUsageMissingCalls, page.usageMissingCalls,
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
        int cacheUsageMissingCalls = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        long cacheCreationInputTokens = 0;
        long cacheReadInputTokens = 0;
        boolean cacheReported = false;
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
                if (span.cacheCreationInputTokens() == null && span.cacheReadInputTokens() == null) {
                    cacheUsageMissingCalls++;
                } else {
                    cacheReported = true;
                    if (span.cacheCreationInputTokens() != null) cacheCreationInputTokens += span.cacheCreationInputTokens();
                    if (span.cacheReadInputTokens() != null) cacheReadInputTokens += span.cacheReadInputTokens();
                }
            } else if ("TOOL".equals(span.kind())) {
                toolCalls++;
                if (Boolean.TRUE.equals(attributes.get("executed"))) executedToolCalls++;
            }
        }
        return new Derived(llmCalls, assistantCalls, summaryCalls, toolCalls, requestedToolCalls,
                executedToolCalls, loopRounds, inputTokens, outputTokens,
                cacheReported ? cacheCreationInputTokens : null,
                cacheReported ? cacheReadInputTokens : null,
                cacheUsageMissingCalls, usageMissingCalls);
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
            long inputTokens, long outputTokens,
            Long cacheCreationInputTokens, Long cacheReadInputTokens,
            int cacheUsageMissingCalls, int usageMissingCalls) {
    }
}
