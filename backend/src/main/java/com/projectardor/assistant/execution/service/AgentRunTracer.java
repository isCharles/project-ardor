package com.projectardor.assistant.execution.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.projectardor.platform.observability.TechnicalRunTrace;
import com.projectardor.platform.observability.TechnicalTracing;

/**
 * One run's trace cursor. Context is passed explicitly through model listeners and tool wrappers;
 * it never relies on ThreadLocal/MDC, so streaming callbacks and asynchronous tool execution stay
 * attached to the correct run.
 */
public final class AgentRunTracer {

    private static final Logger log = LoggerFactory.getLogger(AgentRunTracer.class);
    public static final String PROMPT_VERSION = "agent-system-v5";

    private final AgentTraceStore store;
    private final UUID userId;
    private final UUID runId;
    private final UUID conversationId;
    private final String model;
    private final String promptVersion;
    private final UUID rootSpanId;
    private final TechnicalRunTrace technical;
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
            String model, String promptVersion, UUID rootSpanId, TechnicalRunTrace technical) {
        this.store = store;
        this.userId = userId;
        this.runId = runId;
        this.conversationId = conversationId;
        this.model = model;
        this.promptVersion = promptVersion;
        this.rootSpanId = rootSpanId;
        this.technical = technical;
    }

    public static AgentRunTracer begin(AgentTraceStore store, UUID userId, UUID runId, UUID conversationId,
            String model, String promptVersion) {
        return begin(store, userId, runId, conversationId, model, promptVersion, null);
    }

    public static AgentRunTracer begin(AgentTraceStore store, UUID userId, UUID runId, UUID conversationId,
            String model, String promptVersion, UUID jobId) {
        return begin(store, userId, runId, conversationId, model, promptVersion, jobId, TechnicalTracing.disabled());
    }

    public static AgentRunTracer begin(AgentTraceStore store, UUID userId, UUID runId, UUID conversationId,
            String model, String promptVersion, UUID jobId, TechnicalTracing tracing) {
        String resolvedPromptVersion = promptVersion == null ? PROMPT_VERSION : promptVersion;
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("conversationId", conversationId == null ? "" : conversationId.toString());
        attributes.put("model", model == null ? "" : model);
        attributes.put("promptVersion", resolvedPromptVersion);
        attributes.put("originRun", runId == null ? "" : runId.toString());
        attributes.put("jobId", jobId == null ? "" : jobId.toString());
        boolean traceable = store != null && runId != null;
        TechnicalRunTrace technical = traceable
                ? tracing.startRun("agent.run", runId, conversationId, model, null, resolvedPromptVersion, attributes)
                : TechnicalRunTrace.disabled();
        UUID root = traceable
                ? (technical.started()
                        ? store.startSpan(userId, runId, null, "RUN", "agent.run", attributes,
                                technical.traceId(), technical.spanId())
                        : store.startSpan(userId, runId, null, "RUN", "agent.run", attributes))
                : null;
        if (root != null) {
            technical.attachRoot(root);
            store.linkRunTrace(userId, runId, technical.traceId());
            if (technical.started()) {
                log.info("Agent technical trace linked: runId={}, traceId={}, spanId={}",
                        runId, technical.traceId(), technical.spanId());
            }
        } else if (technical.started()) {
            technical.finish("FAILED", "TRACE_ROOT_WRITE_FAILED");
        }
        return new AgentRunTracer(store, userId, runId, conversationId, model,
                resolvedPromptVersion, root, technical);
    }

    public UUID startChild(String kind, String name, Map<String, Object> attributes) {
        return startChild(null, kind, name, attributes);
    }

    public UUID startChild(UUID parent, String kind, String name, Map<String, Object> attributes) {
        if (!active()) return null;
        UUID businessParent = parent == null ? rootSpanId : parent;
        UUID span = store.startSpan(userId, runId, businessParent, kind, name, attributes);
        if (span != null) {
            technical.startChild(span, businessParent, kind, name, model, null, attributes);
            if (technical.started()) {
                store.linkSpanTrace(userId, span, technical.traceId(), technical.spanIdOf(span));
            }
        }
        if ("CONTEXT".equals(kind) && "context.load".equals(name)) contextSpan.set(span);
        return span;
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
        technical.finishChild(spanId, status, errorCode, modelName, usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens(), usage.cacheReadInputTokens(), attributes);
        contextSpan.compareAndSet(spanId, null);
    }

    /** Adds attributes to a span that already finished, for the outcome the caller learns afterwards. */
    public void annotateChild(UUID spanId, Map<String, Object> attributes) {
        if (!active() || spanId == null || attributes == null || attributes.isEmpty()) return;
        store.annotateSpan(userId, spanId, attributes);
        technical.annotateChild(spanId, attributes);
    }

    public void recordLlm(Integer inTokens, Integer outTokens, boolean toolRound) {
        recordLlm("ASSISTANT", inTokens, outTokens, toolRound, 0);
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
        if (observable) {
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
        technical.finish(status, errorCode);
        if (!"COMPLETED".equals(status)) {
            log.warn("Agent run ended: runId={}, status={}, errorCode={}, traceId={}, spanId={}",
                    runId, status, errorCode, technical.traceId(), technical.spanId());
        }
    }

    public boolean active() {
        return store != null && store.enabled() && rootSpanId != null && !closed.get();
    }

    public UUID userId() { return userId; }
    public UUID runId() { return runId; }
    public UUID rootSpanId() { return rootSpanId; }
    public String technicalTraceId() { return technical.traceId(); }
    public String technicalSpanId() { return technical.spanId(); }
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
