package com.projectardor.assistant.execution.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Attaches each actual LLM invocation to the run tracer. The context is explicit and immutable
 * for the lifetime of the run; streaming callbacks do not depend on MDC or ThreadLocal state.
 */
public final class AgentModelTraceListener implements ChatModelListener {

    private static final String SPAN_ATTRIBUTE = AgentModelTraceListener.class.getName() + ".span";
    private static final String START_NANOS = AgentModelTraceListener.class.getName() + ".startNanos";
    private final AgentRunTracer tracer;
    private final String model;
    private final String purpose;
    private final UUID parent;
    private final AgentMetrics metrics;

    public AgentModelTraceListener(AgentRunTracer tracer, String model) {
        this(tracer, model, "ASSISTANT", null, null);
    }

    public AgentModelTraceListener(AgentRunTracer tracer, String model, String purpose, UUID parent) {
        this(tracer, model, purpose, parent, null);
    }

    public AgentModelTraceListener(AgentRunTracer tracer, String model, AgentMetrics metrics) {
        this(tracer, model, "ASSISTANT", null, metrics);
    }

    public AgentModelTraceListener(AgentRunTracer tracer, String model, String purpose, UUID parent, AgentMetrics metrics) {
        this.tracer = tracer;
        this.model = model;
        this.purpose = purpose;
        this.parent = parent;
        this.metrics = metrics;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START_NANOS, System.nanoTime());
        if (!tracer.active()) return;
        int call = tracer.nextModelCall();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("call", call);
        attributes.put("purpose", purpose);
        attributes.put("model", model == null ? "" : model);
        attributes.put("provider", context.modelProvider() == null ? "" : context.modelProvider().name());
        attributes.put("messages", context.chatRequest() == null || context.chatRequest().messages() == null
                ? 0 : context.chatRequest().messages().size());
        if (context.chatRequest() != null && context.chatRequest().messages() != null) {
            String preview = "";
            for (var message : context.chatRequest().messages()) {
                if (message instanceof UserMessage userMessage) preview = userMessage.toString();
            }
            attributes.put("inputPreview", TracePreview.of(preview));
        }
        String name = "CONTEXT_COMPACTION".equals(purpose) ? "context.compact." + call : "llm.call." + call;
        UUID span = tracer.startChild(parent, "LLM", name, attributes);
        if (span != null) context.attributes().put(SPAN_ATTRIBUTE, span);
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        UUID span = takeSpan(context.attributes());
        long durationNanos = elapsedNanos(context.attributes());
        ChatResponse response = context.chatResponse();
        LlmUsage usage = LlmUsage.of(response == null ? null : response.tokenUsage());
        Integer input = usage.inputTokens();
        Integer output = usage.outputTokens();
        AiMessage message = response == null ? null : response.aiMessage();
        boolean toolRound = message != null && message.hasToolExecutionRequests();
        int requestedTools = toolRound ? message.toolExecutionRequests().size() : 0;
        tracer.recordLlm(purpose, usage, toolRound, requestedTools);
        if (metrics != null) metrics.recordLlmCall(durationNanos, input, output, model, false);
        if (span == null) return;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("finishReason", response == null || response.metadata() == null
                || response.metadata().finishReason() == null ? "" : response.metadata().finishReason().name());
        attributes.put("toolRound", toolRound);
        if (usage.cacheReadInputTokens() != null) attributes.put("cacheReadInputTokens", usage.cacheReadInputTokens());
        if (usage.cacheCreationInputTokens() != null) attributes.put("cacheCreationInputTokens", usage.cacheCreationInputTokens());
        copyTiming(context.attributes(), attributes);
        if (message != null) {
            attributes.put("outputPreview", TracePreview.of(message.text()));
            if (message.hasToolExecutionRequests()) {
                attributes.put("requestedTools", message.toolExecutionRequests().stream()
                        .map(request -> request.name()).toList());
                for (var request : message.toolExecutionRequests()) {
                    tracer.bindToolCall(request.id(), span);
                }
            }
        }
        String actualModel = response == null || response.metadata() == null ? model : response.metadata().modelName();
        tracer.finishChild(span, "COMPLETED", null, actualModel, usage, attributes);
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        UUID span = takeSpan(context.attributes());
        long durationNanos = elapsedNanos(context.attributes());
        tracer.recordLlm(purpose, null, null, false, 0);
        if (metrics != null) metrics.recordLlmCall(durationNanos, null, null, model, true);
        if (span == null) return;
        String errorCode = context.error() == null ? "LLM_CALL_FAILED" : context.error().getClass().getSimpleName();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("errorType", errorCode);
        copyTiming(context.attributes(), attributes);
        tracer.finishChild(span, "FAILED", errorCode, model, null, null, attributes);
    }

    private static void copyTiming(Map<Object, Object> source, Map<String, Object> target) {
        if (source != null && source.remove(TimedStreamingModel.TIMING) instanceof Map<?, ?> timing)
            timing.forEach((key, value) -> {
                if (key instanceof String name && (value instanceof Number || "timingSource".equals(name)))
                    target.put(name, value);
            });
    }

    private static UUID takeSpan(Map<Object, Object> attributes) {
        if (attributes == null) return null;
        Object value = attributes.remove(SPAN_ATTRIBUTE);
        return value instanceof UUID id ? id : null;
    }

    private static long elapsedNanos(Map<Object, Object> attributes) {
        if (attributes == null) return 0L;
        Object start = attributes.get(START_NANOS);
        if (start instanceof Long startNanos) return Math.max(0, System.nanoTime() - startNanos);
        return 0L;
    }
}
