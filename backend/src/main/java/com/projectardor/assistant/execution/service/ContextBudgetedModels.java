package com.projectardor.assistant.execution.service;

import java.util.List;
import java.util.Set;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/** Every round is checked before transport, including newly appended tool results. */
final class ContextBudgetedModels {
    private ContextBudgetedModels() {}
    private static void check(ChatRequest request, AgentContextBudgetPolicy.Budget budget, TokenCountEstimator estimator) {
        long extraOutput = request.maxOutputTokens() == null ? 0
                : Math.max(0L, request.maxOutputTokens().longValue() - budget.outputReserveTokens());
        if (AgentRequestTokens.estimate(request, estimator) > Math.max(0, budget.hardInputTokens() - extraOutput))
            throw new AgentContextLimitException();
    }
    static ChatModel chat(ChatModel delegate, AgentContextBudgetPolicy.Budget budget, TokenCountEstimator estimator) {
        if (budget.hardInputTokens() == null) return delegate;
        return new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) { check(request, budget, estimator); return delegate.doChat(request); }
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
        };
    }
    static StreamingChatModel streaming(StreamingChatModel delegate, AgentContextBudgetPolicy.Budget budget, TokenCountEstimator estimator) {
        if (budget.hardInputTokens() == null) return delegate;
        return new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                try { check(request, budget, estimator); }
                catch (AgentContextLimitException limit) { handler.onError(limit); return; }
                delegate.doChat(request, handler);
            }
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
        };
    }
}
