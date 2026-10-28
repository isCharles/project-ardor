package com.projectardor.assistant.execution.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectardor.platform.model.llm.service.ModelCallCapacity;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.*;

/** Holds one shared provider slot for the lifetime of each model invocation. */
final class CapacityLimitedModels {
    private CapacityLimitedModels() {}

    /** Identifies who and which model a call is charged to for the optional rate window. */
    record CallScope(String surface, UUID userId, String modelKey, long reservedTokens,
            TokenCountEstimator estimator) {
        long tokens(ChatRequest request) {
            return Math.max(0, AgentRequestTokens.estimate(request, estimator))
                    + Math.max(0, reservedTokens);
        }
    }

    static ChatModel chat(ChatModel delegate, ModelCallCapacity capacity) {
        return chat(delegate, capacity, null);
    }

    static ChatModel chat(ChatModel delegate, ModelCallCapacity capacity, CallScope scope) {
        return new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                if (!acquire(capacity, scope, request)) throw capacity.busy();
                try {
                    return delegate.doChat(request);
                } finally {
                    capacity.release();
                }
            }
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
        };
    }

    static StreamingChatModel streaming(StreamingChatModel delegate, ModelCallCapacity capacity) {
        return streaming(delegate, capacity, null);
    }

    static StreamingChatModel streaming(StreamingChatModel delegate, ModelCallCapacity capacity, CallScope scope) {
        return new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler downstream) {
                if (!acquire(capacity, scope, request)) {
                    downstream.onError(capacity.busy());
                    return;
                }
                AtomicBoolean released = new AtomicBoolean();
                StreamingChatResponseHandler measured = new StreamingChatResponseHandler() {
                    private void release() {
                        if (released.compareAndSet(false, true)) capacity.release();
                    }
                    @Override public void onPartialResponse(String text) { downstream.onPartialResponse(text); }
                    @Override public void onPartialResponse(PartialResponse part, PartialResponseContext context) { downstream.onPartialResponse(part, context); }
                    @Override public void onPartialThinking(PartialThinking part) { downstream.onPartialThinking(part); }
                    @Override public void onPartialThinking(PartialThinking part, PartialThinkingContext context) { downstream.onPartialThinking(part, context); }
                    @Override public void onPartialToolCall(PartialToolCall part) { downstream.onPartialToolCall(part); }
                    @Override public void onPartialToolCall(PartialToolCall part, PartialToolCallContext context) { downstream.onPartialToolCall(part, context); }
                    @Override public void onCompleteToolCall(CompleteToolCall call) { downstream.onCompleteToolCall(call); }
                    @Override public void onUnmappedRawEvent(Object event) { downstream.onUnmappedRawEvent(event); }
                    @Override public void onCompleteResponse(ChatResponse response) {
                        release();
                        downstream.onCompleteResponse(response);
                    }
                    @Override public void onError(Throwable error) {
                        release();
                        downstream.onError(error);
                    }
                };
                try {
                    delegate.doChat(request, measured);
                } catch (RuntimeException failure) {
                    if (released.get()) throw failure;
                    measured.onError(failure);
                }
            }
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
        };
    }

    private static boolean acquire(ModelCallCapacity capacity, CallScope scope, ChatRequest request) {
        if (scope == null) return capacity.tryAcquire("agent");
        return capacity.tryAcquire(scope.surface(), scope.userId(), scope.modelKey(), scope.tokens(request));
    }
}
