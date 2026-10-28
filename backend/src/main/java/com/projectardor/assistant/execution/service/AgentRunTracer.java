package com.projectardor.assistant.execution.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One run's trace cursor. Context is passed explicitly through model listeners and tool wrappers;
 * it never relies on ThreadLocal/MDC, so streaming callbacks and asynchronous tool execution stay
 * attached to the correct run.
 */
public final class AgentRunTracer {

    public static final String PROMPT_VERSION = "agent-system-v5";

    private final AgentTraceStore store;
    private final UUID userId;
    private final UUID runId;
    private final UUID conversationId;
    private final String model;
    private final String promptVersion;
    private final UUID rootSpanId;
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicInteger assistantCalls = new AtomicInteger();
    private final AtomicInteger summaryCalls = new AtomicInteger();
    private final AtomicInteger modelCallSequence = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<UUID> contextSpan = new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger requestedToolCalls = new AtomicInteger();
    private final AtomicInteger executedToolCalls = new AtomicInteger();
    private final AtomicInteger loopRounds = new AtomicInteger();
    private final AtomicInteger usageMissingCalls = new AtomicInteger();
    private final AtomicInteger cacheUsageMissingCalls = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cacheReadInputTokens = new AtomicLong();
    private final AtomicLong cacheCreationInputTokens = new AtomicLong();
    private final AtomicBoolean cacheUsageReported = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.ConcurrentHashMap<String, UUID> toolCallParents =
            new java.util.concurrent.ConcurrentHashMap<>();

    private AgentRunTracer(AgentTraceStore store, UUID userId, UUID runId, UUID conversationId,
            String model, String promptVersion, UUID rootSpanId) {
        this.store = store;
        this.userId = userId;
        this.runId = runId;
        this.conversationId = conversationId;
        this.model = model;
        this.promptVersion = promptVersion;
        this.rootSpanId = rootSpanId;
    }

    public static AgentRunTracer begin(AgentTraceStore store, UUID userId, UUID runId, UUID conversationId,
            String model, String promptVersion) {
        return begin(store, userId, runId, conversationId, model, promptVersion, null);
    }

    public static AgentRunTracer begin(AgentTraceStore store, UUID userId, UUID runId, UUID conversationId,
            String model, String promptVersion, UUID jobId) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("conversationId", conversationId == null ? "" : conversationId.toString());
        attributes.put("model", model == null ? "" : model);
        attributes.put("promptVersion", promptVersion == null ? "" : promptVersion);
        attributes.put("originRun", runId == null ? "" : runId.toString());
        attributes.put("jobId", jobId == null ? "" : jobId.toString());
        UUID root = store == null ? null : store.startSpan(userId, runId, null, "RUN", "agent.run", attributes);
        return new AgentRunTracer(store, userId, runId, conversationId, model,
                promptVersion == null ? PROMPT_VERSION : promptVersion, root);
    }

    public UUID startChild(String kind, String name, Map<String, Object> attributes) {
        if (!active()) return null;
        UUID span = store.startSpan(userId, runId, rootSpanId, kind, name, attributes);
        if ("CONTEXT".equals(kind) && "context.load".equals(name)) contextSpan.set(span);
        return span;
    }

    public UUID startChild(UUID parent, String kind, String name, Map<String, Object> attributes) {
        if (!active()) return null;
        return store.startSpan(userId, runId, parent == null ? rootSpanId : parent, kind, name, attributes);
    }

    public UUID contextSpanId() { return contextSpan.get(); }
    public int nextModelCall() { return modelCallSequence.incrementAndGet(); }

    /** Links a parsed tool-call id to the LLM span that requested it. */
    public void bindToolCall(String toolCallId, UUID llmSpanId) {
        if (toolCallId == null || toolCallId.isBlank() || llmSpanId == null) return;
        toolCallParents.put(toolCallId, llmSpanId);
    }

    public UUID toolCallParent(String toolCallId) {
        return toolCallId == null ? null : toolCallParents.get(toolCallId);
    }

    public void finishChild(UUID spanId, String status, String errorCode, String modelName,
            Integer inTokens, Integer outTokens, Map<String, Object> attributes) {
        finishChild(spanId, status, errorCode, modelName,
                new LlmUsage(inTokens, outTokens, null, null), attributes);
    }

    public void finishChild(UUID spanId, String status, String errorCode, String modelName,
            LlmUsage usage, Map<String, Object> attributes) {
        if (!active() || spanId == null) return;
        store.finishSpan(userId, spanId, status, errorCode, modelName, promptVersion,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens(), usage.cacheReadInputTokens(), attributes);
        contextSpan.compareAndSet(spanId, null);
    }

    /** Adds attributes to a span that already finished, for the outcome the caller learns afterwards. */
    public void annotateChild(UUID spanId, Map<String, Object> attributes) {
        if (!active() || spanId == null || attributes == null || attributes.isEmpty()) return;
        store.annotateSpan(userId, spanId, attributes);
    }

    public void recordLlm(Integer inTokens, Integer outTokens, boolean toolRound) {        recordLlm("ASSISTANT", inTokens, outTokens, toolRound, 0);
    }

    /** Counts one LLM invocation by purpose, including calls whose provider omitted usage. */
    public void recordLlm(String purpose, Integer inTokens, Integer outTokens, boolean toolRound, int requestedTools) {
        recordLlm(purpose, new LlmUsage(inTokens, outTokens, null, null), toolRound, requestedTools);
    }

    public void recordLlm(String purpose, LlmUsage usage, boolean toolRound, int requestedTools) {
        // Metering is a run fact, not a side effect of optional span persistence.
        if (closed.get()) return;
        llmCalls.incrementAndGet();
        if ("CONTEXT_COMPACTION".equals(purpose)) summaryCalls.incrementAndGet();
        else assistantCalls.incrementAndGet();
        if (toolRound) loopRounds.incrementAndGet();
        if (requestedTools > 0) requestedToolCalls.addAndGet(requestedTools);
        Integer inTokens = usage.inputTokens();
        Integer outTokens = usage.outputTokens();
        if (inTokens == null || outTokens == null) usageMissingCalls.incrementAndGet();
        if (inTokens != null) inputTokens.addAndGet(Math.max(0, inTokens));
        if (outTokens != null) outputTokens.addAndGet(Math.max(0, outTokens));
        if (usage.hasCacheFields()) {
            cacheUsageReported.set(true);
            if (usage.cacheReadInputTokens() != null) cacheReadInputTokens.addAndGet(Math.max(0, usage.cacheReadInputTokens()));
            if (usage.cacheCreationInputTokens() != null) cacheCreationInputTokens.addAndGet(Math.max(0, usage.cacheCreationInputTokens()));
        } else {
            cacheUsageMissingCalls.incrementAndGet();
        }
    }

    public void recordTool() {
        if (!closed.get()) toolCalls.incrementAndGet();
    }

    /** A wrapper invocation that actually reached the business delegate. */
    public void recordToolExecuted() {
        if (!closed.get()) executedToolCalls.incrementAndGet();
    }

    public void complete() {
        finish("COMPLETED", null);
    }

    public void fail(String errorCode) {
        finish("FAILED", errorCode);
    }

    private void finish(String status, String errorCode) {
        boolean observable = active();
        if (!closed.compareAndSet(false, true)) return;
        if (!observable) return;
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("llmCalls", llmCalls.get());
        summary.put("assistantCalls", assistantCalls.get());
        summary.put("summaryCalls", summaryCalls.get());
        summary.put("toolCalls", toolCalls.get());
        summary.put("requestedToolCalls", requestedToolCalls.get());
        summary.put("executedToolCalls", executedToolCalls.get());
        summary.put("loopRounds", loopRounds.get());
        summary.put("usageMissingCalls", usageMissingCalls.get());
        summary.put("cacheUsageMissingCalls", cacheUsageMissingCalls.get());
        long cacheRead = cacheReadInputTokens.get();
        long cacheCreation = cacheCreationInputTokens.get();
        boolean cacheReported = cacheUsageReported.get();
        if (cacheReported) {
            summary.put("cacheReadInputTokens", cacheRead);
            summary.put("cacheCreationInputTokens", cacheCreation);
        }
        store.finishSpan(userId, rootSpanId, status, errorCode, model, promptVersion,
                (int) Math.min(Integer.MAX_VALUE, inputTokens.get()),
                (int) Math.min(Integer.MAX_VALUE, outputTokens.get()),
                cacheReported ? (int) Math.min(Integer.MAX_VALUE, cacheCreation) : null,
                cacheReported ? (int) Math.min(Integer.MAX_VALUE, cacheRead) : null,
                summary);
        store.updateRunAggregates(userId, runId, llmCalls.get(), toolCalls.get(), loopRounds.get(),
                inputTokens.get(), outputTokens.get(), cacheCreation, cacheRead, cacheReported, model, promptVersion);
    }

    public boolean active() {
        return store != null && store.enabled() && rootSpanId != null && !closed.get();
    }

    public UUID userId() { return userId; }
    public UUID runId() { return runId; }
    public UUID rootSpanId() { return rootSpanId; }
    public String model() { return model; }
    public String promptVersion() { return promptVersion; }
    public int llmCalls() { return llmCalls.get(); }
    public int assistantCalls() { return assistantCalls.get(); }
    public int summaryCalls() { return summaryCalls.get(); }
    public int toolCalls() { return toolCalls.get(); }
    public int requestedToolCalls() { return requestedToolCalls.get(); }
    public int executedToolCalls() { return executedToolCalls.get(); }
    public int loopRounds() { return loopRounds.get(); }
    public int usageMissingCalls() { return usageMissingCalls.get(); }
    public long inputTokens() { return inputTokens.get(); }
    public long outputTokens() { return outputTokens.get(); }
    public long cacheReadInputTokens() { return cacheReadInputTokens.get(); }
    public long cacheCreationInputTokens() { return cacheCreationInputTokens.get(); }
    public int cacheUsageMissingCalls() { return cacheUsageMissingCalls.get(); }
    public boolean cacheUsageReported() { return cacheUsageReported.get(); }
    public boolean closed() { return closed.get(); }
}
