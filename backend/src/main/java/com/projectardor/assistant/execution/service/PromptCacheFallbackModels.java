package com.projectardor.assistant.execution.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * Retries once without prompt caching when a relay rejects {@code cache_control}, then remembers
 * the endpoint for the rest of the process so later calls skip the doomed cached attempt.
 */
final class PromptCacheFallbackModels {
    private static final Set<String> UNSUPPORTED = ConcurrentHashMap.newKeySet();

    private PromptCacheFallbackModels() {}

    static ChatModel chat(ChatModel cached, Supplier<ChatModel> plain, String fingerprint) {
        return new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                if (UNSUPPORTED.contains(fingerprint)) return plain.get().doChat(request);
                try {
                    return cached.doChat(request);
                } catch (RuntimeException failure) {
                    if (!cacheRelated(failure)) throw failure;
                    ChatResponse response = plain.get().doChat(request);
                    UNSUPPORTED.add(fingerprint);
                    return response;
                }
            }
            @Override public ChatRequestParameters defaultRequestParameters() { return cached.defaultRequestParameters(); }
            @Override public List<ChatModelListener> listeners() { return cached.listeners(); }
            @Override public ModelProvider provider() { return cached.provider(); }
            @Override public Set<Capability> supportedCapabilities() { return cached.supportedCapabilities(); }
        };
    }

    static StreamingChatModel streaming(StreamingChatModel cached, Supplier<StreamingChatModel> plain,
            String fingerprint) {
        return new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler downstream) {
                if (UNSUPPORTED.contains(fingerprint)) {
                    plain.get().doChat(request, downstream);
                    return;
                }
                AtomicBoolean emitted = new AtomicBoolean();
                StreamingChatResponseHandler guard = new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String text) {
                        emitted.set(true);
                        downstream.onPartialResponse(text);
                    }
                    @Override public void onPartialResponse(PartialResponse part, PartialResponseContext context) {
                        emitted.set(true);
                        downstream.onPartialResponse(part, context);
                    }
                    @Override public void onPartialThinking(PartialThinking part) {
                        emitted.set(true);
                        downstream.onPartialThinking(part);
                    }
                    @Override public void onPartialThinking(PartialThinking part, PartialThinkingContext context) {
                        emitted.set(true);
                        downstream.onPartialThinking(part, context);
                    }
                    @Override public void onPartialToolCall(PartialToolCall part) {
                        emitted.set(true);
                        downstream.onPartialToolCall(part);
                    }
                    @Override public void onPartialToolCall(PartialToolCall part, PartialToolCallContext context) {
                        emitted.set(true);
                        downstream.onPartialToolCall(part, context);
                    }
                    @Override public void onCompleteToolCall(CompleteToolCall call) {
                        downstream.onCompleteToolCall(call);
                    }
                    @Override public void onUnmappedRawEvent(Object event) {
                        downstream.onUnmappedRawEvent(event);
                    }
                    @Override public void onCompleteResponse(ChatResponse response) {
                        downstream.onCompleteResponse(response);
                    }
                    @Override public void onError(Throwable error) {
                        if (!emitted.get() && cacheRelated(error)) {
                            UNSUPPORTED.add(fingerprint);
                            plain.get().doChat(request, downstream);
                            return;
                        }
                        downstream.onError(error);
                    }
                };
                cached.doChat(request, guard);
            }
            @Override public ChatRequestParameters defaultRequestParameters() { return cached.defaultRequestParameters(); }
            @Override public List<ChatModelListener> listeners() { return cached.listeners(); }
            @Override public ModelProvider provider() { return cached.provider(); }
            @Override public Set<Capability> supportedCapabilities() { return cached.supportedCapabilities(); }
        };
    }

    private static boolean cacheRelated(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("cache")) return true;
        }
        return false;
    }
}
