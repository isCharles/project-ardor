package com.projectardor.assistant.execution.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.*;

/** Per-invocation timing. No request state is shared between tool rounds or concurrent streams. */
final class TimedStreamingModel implements StreamingChatModel {
    static final String TIMING = TimedStreamingModel.class.getName();
    private final StreamingChatModel delegate;
    private final List<AgentModelTraceListener> traces;
    private final LongSupplier clock;

    static StreamingChatModel wrap(StreamingChatModel model) {
        return model.listeners().stream().anyMatch(AgentModelTraceListener.class::isInstance)
                ? new TimedStreamingModel(model, System::nanoTime) : model;
    }

    TimedStreamingModel(StreamingChatModel delegate, LongSupplier clock) {
        this.delegate = delegate;
        this.clock = clock;
        this.traces = delegate.listeners().stream().filter(AgentModelTraceListener.class::isInstance)
                .map(AgentModelTraceListener.class::cast).toList();
    }

    @Override public List<ChatModelListener> listeners() {
        // Trace completion must see timing before the SDK's final callback. Other listeners retain SDK lifecycle.
        return delegate.listeners().stream().filter(listener -> !(listener instanceof AgentModelTraceListener)).toList();
    }
    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }

    @Override public void doChat(ChatRequest request, StreamingChatResponseHandler downstream) {
        Map<Object, Object> attributes = new HashMap<>();
        traces.forEach(trace -> trace.onRequest(new ChatModelRequestContext(request, provider(), attributes)));
        long started = clock.getAsLong();
        AtomicLong first = new AtomicLong(Long.MIN_VALUE);
        AtomicBoolean terminal = new AtomicBoolean();
        StreamingChatResponseHandler measured = new StreamingChatResponseHandler() {
            private void content(boolean present) {
                if (present && !terminal.get()) first.compareAndSet(Long.MIN_VALUE, clock.getAsLong());
            }
            private void timing(ChatResponse response) {
                long elapsed = Math.max(0, clock.getAsLong() - started);
                Map<String, Object> values = new java.util.LinkedHashMap<>();
                values.put("timingSource", "client-monotonic-stream-v1");
                values.put("requestDurationMs", elapsed / 1_000_000.0);
                long firstAt = first.get();
                if (firstAt != Long.MIN_VALUE) {
                    values.put("firstContentMs", Math.max(0, firstAt - started) / 1_000_000.0);
                    values.put("streamAfterFirstContentMs", Math.max(0, elapsed - (firstAt - started)) / 1_000_000.0);
                }
                Integer tokens = response == null || response.tokenUsage() == null ? null : response.tokenUsage().outputTokenCount();
                if (tokens != null && tokens >= 0 && elapsed > 0)
                    values.put("outputTokensPerRequestSecond", tokens * 1_000_000_000.0 / elapsed);
                attributes.put(TIMING, values);
            }
            @Override public void onPartialResponse(String text) { content(text != null && !text.isEmpty()); downstream.onPartialResponse(text); }
            @Override public void onPartialResponse(PartialResponse part, PartialResponseContext context) { content(part != null && part.text() != null && !part.text().isEmpty()); downstream.onPartialResponse(part, context); }
            @Override public void onPartialThinking(PartialThinking part) { content(part != null && part.text() != null && !part.text().isEmpty()); downstream.onPartialThinking(part); }
            @Override public void onPartialThinking(PartialThinking part, PartialThinkingContext context) { content(part != null && part.text() != null && !part.text().isEmpty()); downstream.onPartialThinking(part, context); }
            @Override public void onPartialToolCall(PartialToolCall part) { content(part != null); downstream.onPartialToolCall(part); }
            @Override public void onPartialToolCall(PartialToolCall part, PartialToolCallContext context) { content(part != null); downstream.onPartialToolCall(part, context); }
            @Override public void onCompleteToolCall(CompleteToolCall call) { downstream.onCompleteToolCall(call); }
            @Override public void onUnmappedRawEvent(Object event) { downstream.onUnmappedRawEvent(event); }
            @Override public void onCompleteResponse(ChatResponse response) {
                if (!terminal.compareAndSet(false, true)) return;
                timing(response);
                traces.forEach(trace -> trace.onResponse(new ChatModelResponseContext(response, request, provider(), attributes)));
                downstream.onCompleteResponse(response);
            }
            @Override public void onError(Throwable error) {
                if (!terminal.compareAndSet(false, true)) return;
                timing(null);
                traces.forEach(trace -> trace.onError(new ChatModelErrorContext(error, request, provider(), attributes)));
                downstream.onError(error);
            }
        };
        try { delegate.doChat(request, measured); }
        catch (RuntimeException failure) {
            if (terminal.get()) throw failure;
            measured.onError(failure);
        }
    }
}
