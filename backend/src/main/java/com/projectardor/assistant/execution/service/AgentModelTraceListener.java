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
import dev.langchain4j.model.output.TokenUsage;

/**
 * Attaches each actual LLM invocation to the run tracer. The context is explicit and immutable
 * for the lifetime of the run; streaming callbacks do not depend on MDC or ThreadLocal state.
 */
public final class AgentModelTraceListener implements ChatModelListener {

    private static final String SPAN_ATTRIBUTE = AgentModelTraceListener.class.getName() + ".span";
    private final AgentRunTracer tracer;
    private final String model;
    private final String purpose;
    private final UUID parent;

    public AgentModelTraceListener(AgentRunTracer tracer, String model) {
        this(tracer, model, "ASSISTANT", null);
    }

    public AgentModelTraceListener(AgentRunTracer tracer, String model, String purpose, UUID parent) {
        this.tracer = tracer;
        this.model = model;
        this.purpose = purpose;
        this.parent = parent;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
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
        ChatResponse response = context.chatResponse();
        TokenUsage usage = response == null ? null : response.tokenUsage();
        Integer input = usage == null ? null : usage.inputTokenCount();
        Integer output = usage == null ? null : usage.outputTokenCount();
        AiMessage message = response == null ? null : response.aiMessage();
        boolean toolRound = message != null && message.hasToolExecutionRequests();
        tracer.recordLlm(input, output, toolRound);
        if (span == null) return;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("finishReason", response == null || response.metadata() == null
                || response.metadata().finishReason() == null ? "" : response.metadata().finishReason().name());
        attributes.put("toolRound", toolRound);
        if (message != null) {
            attributes.put("outputPreview", TracePreview.of(message.text()));
            if (message.hasToolExecutionRequests()) {
                attributes.put("requestedTools", message.toolExecutionRequests().stream()
                        .map(request -> request.name()).toList());
            }
        }
        String actualModel = response == null || response.metadata() == null ? model : response.metadata().modelName();
        tracer.finishChild(span, "COMPLETED", null, actualModel, input, output, attributes);
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        UUID span = takeSpan(context.attributes());
        tracer.recordLlm(null, null, false);
        if (span == null) return;
        String errorCode = context.error() == null ? "LLM_CALL_FAILED" : context.error().getClass().getSimpleName();
        tracer.finishChild(span, "FAILED", errorCode, model, null, null,
                Map.of("errorType", errorCode));
    }

    private static UUID takeSpan(Map<Object, Object> attributes) {
        if (attributes == null) return null;
        Object value = attributes.remove(SPAN_ATTRIBUTE);
        return value instanceof UUID id ? id : null;
    }
}
