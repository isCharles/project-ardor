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

    public static final String PROMPT_VERSION = "agent-system-v1";

    private final AgentTraceStore store;
    private final UUID userId;
    private final UUID runId;
    private final UUID conversationId;
    private final String model;
    private final String promptVersion;
    private final UUID rootSpanId;
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger loopRounds = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

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
        UUID root = store == null ? null : store.startSpan(userId, runId, null, "RUN", "agent.run",
                Map.of("conversationId", conversationId == null ? "" : conversationId.toString(),
                        "model", model == null ? "" : model,
                        "promptVersion", promptVersion == null ? "" : promptVersion));
        return new AgentRunTracer(store, userId, runId, conversationId, model,
                promptVersion == null ? PROMPT_VERSION : promptVersion, root);
    }

    public UUID startChild(String kind, String name, Map<String, Object> attributes) {
        if (!active()) return null;
        return store.startSpan(userId, runId, rootSpanId, kind, name, attributes);
    }

    public void finishChild(UUID spanId, String status, String errorCode, String modelName,
            Integer inTokens, Integer outTokens, Map<String, Object> attributes) {
        if (!active() || spanId == null) return;
        store.finishSpan(userId, spanId, status, errorCode, modelName, promptVersion,
                inTokens, outTokens, attributes);
    }

    public void recordLlm(Integer inTokens, Integer outTokens, boolean toolRound) {
        if (!active()) return;
        llmCalls.incrementAndGet();
        if (toolRound) loopRounds.incrementAndGet();
        if (inTokens != null) inputTokens.addAndGet(inTokens);
        if (outTokens != null) outputTokens.addAndGet(outTokens);
    }

    public void recordTool() {
        if (active()) toolCalls.incrementAndGet();
    }

    public void complete() {
        finish("COMPLETED", null);
    }

    public void fail(String errorCode) {
        finish("FAILED", errorCode);
    }

    private void finish(String status, String errorCode) {
        if (!active() || !closed.compareAndSet(false, true)) return;
        store.finishSpan(userId, rootSpanId, status, errorCode, model, promptVersion,
                (int) Math.min(Integer.MAX_VALUE, inputTokens.get()),
                (int) Math.min(Integer.MAX_VALUE, outputTokens.get()),
                Map.of("llmCalls", llmCalls.get(), "toolCalls", toolCalls.get(), "loopRounds", loopRounds.get()));
        store.updateRunAggregates(userId, runId, llmCalls.get(), toolCalls.get(), loopRounds.get(),
                inputTokens.get(), outputTokens.get(), model, promptVersion);
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
    public int toolCalls() { return toolCalls.get(); }
    public int loopRounds() { return loopRounds.get(); }
    public boolean closed() { return closed.get(); }
}
