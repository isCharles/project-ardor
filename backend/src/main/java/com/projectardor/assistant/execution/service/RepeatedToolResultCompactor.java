package com.projectardor.assistant.execution.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.projectardor.assistant.tools.capability.ToolCatalog;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/** Compact identical read-only observations in a request copy, retaining both endpoint results. */
final class RepeatedToolResultCompactor {
    private static final int MIN_RESULT_LENGTH = 1024;
    private RepeatedToolResultCompactor() {}
    private record Key(String tool, String arguments, String text, Boolean error, Map<String, Object> attributes) {}
    private record Call(int position, ToolExecutionRequest request) {}

    static ChatRequest compact(ChatRequest request, AgentContextBudgetPolicy.Budget budget, TokenCountEstimator estimator) {
        if (!budget.shouldCompact(AgentRequestTokens.estimate(request, estimator))) return request;
        List<ChatMessage> messages = request.messages();
        Map<String, Call> calls = new HashMap<>();
        Set<String> ambiguous = new HashSet<>(), resultIds = new HashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests())
                for (var call : ai.toolExecutionRequests()) {
                    if (call.id() == null || call.id().isBlank() || calls.putIfAbsent(call.id(), new Call(index, call)) != null)
                        ambiguous.add(call.id());
                }
            if (message instanceof ToolExecutionResultMessage result && !resultIds.add(result.id())) ambiguous.add(result.id());
        }
        Map<Key, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolExecutionResultMessage result)) continue;
            var registeredCall = calls.get(result.id());
            if (registeredCall == null || registeredCall.position() >= i) continue;
            var call = registeredCall.request();
            if (ambiguous.contains(result.id()) || !java.util.Objects.equals(call.name(), result.toolName())
                    || call.arguments() == null || Boolean.TRUE.equals(result.isError()) || !result.hasSingleText()
                    || result.text() == null || result.text().length() < MIN_RESULT_LENGTH) continue;
            var risk = ToolCatalog.describe(call.name());
            if (risk == null || risk.capability() != com.projectardor.assistant.tools.capability.Capability.READ) continue;
            groups.computeIfAbsent(new Key(call.name(), call.arguments(), result.text(), result.isError(), result.attributes()), key -> new ArrayList<>()).add(i);
        }
        List<ChatMessage> copy = null;
        for (var indexes : groups.values()) {
            if (indexes.size() < 3) continue;
            String firstId = ((ToolExecutionResultMessage) messages.get(indexes.getFirst())).id();
            for (int i = 1; i < indexes.size() - 1; i++) {
                int position = indexes.get(i);
                var original = (ToolExecutionResultMessage) messages.get(position);
                String note = "[重复的只读工具结果：与调用 ID " + firstId + " 的完整结果完全一致。]";
                if (note.length() >= original.text().length()) continue;
                if (copy == null) copy = new ArrayList<>(messages);
                copy.set(position, ToolExecutionResultMessage.builder().id(original.id()).toolName(original.toolName())
                        .text(note).isError(original.isError()).attributes(original.attributes()).build());
            }
        }
        return copy == null ? request : request.toBuilder().messages(copy).build();
    }

    static StreamingChatModel streaming(StreamingChatModel delegate, AgentContextBudgetPolicy.Budget budget, TokenCountEstimator estimator) {
        return new StreamingChatModel() {
            @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) { delegate.chat(compact(request, budget, estimator), handler); }
            @Override public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) { delegate.chat(compact(request, budget, estimator), options, handler); }
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { delegate.doChat(compact(request, budget, estimator), handler); }
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
        };
    }

    static ChatModel chat(ChatModel delegate, AgentContextBudgetPolicy.Budget budget, TokenCountEstimator estimator) {
        return new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { return delegate.chat(compact(request, budget, estimator)); }
            @Override public ChatResponse chat(ChatRequest request, ChatRequestOptions options) { return delegate.chat(compact(request, budget, estimator), options); }
            @Override public ChatResponse doChat(ChatRequest request) { return delegate.doChat(compact(request, budget, estimator)); }
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
        };
    }
}
