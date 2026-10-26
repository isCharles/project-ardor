package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

class ContextBudgetedModelsTests {
    private final AgentContextBudgetPolicy.Budget budget = new AgentContextBudgetPolicy.Budget(1000L, 500, 700L, 200, 100);

    @Test void newlyReturnedToolPayloadIsFencedBeforeTheNextTransportCall() {
        var delegate = mock(StreamingChatModel.class);
        var wrapper = ContextBudgetedModels.streaming(delegate, budget, null);
        var handler = mock(StreamingChatResponseHandler.class);
        var first = ChatRequest.builder().messages(UserMessage.from("question")).build();
        wrapper.doChat(first, handler);
        verify(delegate).doChat(first, handler);
        clearInvocations(delegate, handler);
        var second = ChatRequest.builder().messages(UserMessage.from("question"),
                ToolExecutionResultMessage.from("call", "read", "untrusted large result ".repeat(100))).build();
        wrapper.doChat(second, handler);
        verifyNoInteractions(delegate);
        verify(handler).onError(any(AgentContextLimitException.class));
    }

    @Test void wrapperPreservesProviderDefaultsCapabilitiesAndListeners() {
        var delegate = mock(StreamingChatModel.class);
        var parameters = ChatRequestParameters.builder().modelName("configured").maxOutputTokens(200).build();
        when(delegate.defaultRequestParameters()).thenReturn(parameters);
        when(delegate.provider()).thenReturn(ModelProvider.OPEN_AI);
        when(delegate.supportedCapabilities()).thenReturn(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
        when(delegate.listeners()).thenReturn(List.of());
        var wrapper = ContextBudgetedModels.streaming(delegate, budget, null);
        assertThat(wrapper.defaultRequestParameters()).isSameAs(parameters);
        assertThat(wrapper.provider()).isEqualTo(delegate.provider());
        assertThat(wrapper.supportedCapabilities()).isEqualTo(delegate.supportedCapabilities());
        assertThat(wrapper.listeners()).isSameAs(delegate.listeners());
    }

    @Test void oversizedSummaryAndIncreasedOutputReservationAreRejectedLocally() {
        var delegate = mock(ChatModel.class);
        var wrapper = ContextBudgetedModels.chat(delegate, budget, null);
        var summary = ChatRequest.builder().messages(UserMessage.from("old history ".repeat(200))).build();
        assertThatThrownBy(() -> wrapper.doChat(summary)).isInstanceOf(AgentContextLimitException.class);
        var request = ChatRequest.builder().messages(UserMessage.from("x".repeat(900)))
                .parameters(ChatRequestParameters.builder().maxOutputTokens(500).build()).build();
        assertThatThrownBy(() -> wrapper.doChat(request)).isInstanceOf(AgentContextLimitException.class);
        verifyNoInteractions(delegate);
    }

    @Test void unknownWindowIsNotEnforcedAsAnInventedCapacity() {
        var delegate = mock(ChatModel.class);
        var unknown = new AgentContextBudgetPolicy.Budget(null, 12000, null, 4096, 0);
        assertThat(ContextBudgetedModels.chat(delegate, unknown, null)).isSameAs(delegate);
    }
}
