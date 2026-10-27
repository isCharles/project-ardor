package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.projectardor.platform.model.llm.service.LlmCallException;
import com.projectardor.platform.model.llm.service.ModelCallCapacity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

class CapacityLimitedModelsTests {

    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("synthetic")).build();
    private final ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();

    @Test
    void chatCallReleasesItsSlotAfterSuccessAndFailure() {
        var capacity = new ModelCallCapacity(1);
        var delegate = mock(ChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        when(delegate.doChat(any())).thenReturn(response).thenThrow(new IllegalStateException("transport"));
        var model = CapacityLimitedModels.chat(delegate, capacity);

        assertThat(model.doChat(request)).isSameAs(response);
        assertThatThrownBy(() -> model.doChat(request)).isInstanceOf(IllegalStateException.class);

        assertThat(capacity.tryAcquire("agent")).isTrue();
        assertThat(capacity.tryAcquire("agent")).isFalse();
        capacity.release();
    }

    @Test
    void chatCallFailsFastWhenEverySlotIsHeld() {
        var capacity = new ModelCallCapacity(1);
        var delegate = mock(ChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        var model = CapacityLimitedModels.chat(delegate, capacity);
        capacity.tryAcquire("other");

        assertThatThrownBy(() -> model.doChat(request))
                .isInstanceOf(LlmCallException.class)
                .satisfies(failure -> {
                    var llm = (LlmCallException) failure;
                    assertThat(llm.getCode()).isEqualTo("LLM_BUSY");
                    assertThat(llm.isRetryable()).isTrue();
                });
        capacity.release();
    }

    @Test
    void streamingReleasesAfterCompletionAndForwardsTheResponse() {
        var capacity = new ModelCallCapacity(1);
        var delegate = mock(StreamingChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        doAnswer(call -> {
            call.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(response);
            return null;
        }).when(delegate).doChat(any(), any());
        var downstream = new RecordingHandler();
        var model = CapacityLimitedModels.streaming(delegate, capacity);

        model.doChat(request, downstream);

        assertThat(downstream.response).isSameAs(response);
        assertThat(downstream.error).isNull();
        assertThat(capacity.tryAcquire("agent")).isTrue();
        assertThat(capacity.tryAcquire("agent")).isFalse();
        capacity.release();
    }

    @Test
    void streamingReleasesAfterErrorsAndSynchronousThrowsExactlyOnce() {
        var capacity = new ModelCallCapacity(1);
        var delegate = mock(StreamingChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        doAnswer(call -> {
            call.<StreamingChatResponseHandler>getArgument(1).onError(new IllegalStateException("stream"));
            throw new IllegalStateException("stream");
        }).when(delegate).doChat(any(), any());
        var downstream = new RecordingHandler();
        var model = CapacityLimitedModels.streaming(delegate, capacity);

        assertThatThrownBy(() -> model.doChat(request, downstream)).isInstanceOf(IllegalStateException.class);
        assertThat(downstream.error).isInstanceOf(IllegalStateException.class);
        assertThat(capacity.tryAcquire("agent")).isTrue();
        assertThat(capacity.tryAcquire("agent")).isFalse();
        capacity.release();
    }

    @Test
    void streamingRejectsWithoutCallingTheDelegateWhenEverySlotIsHeld() {
        var capacity = new ModelCallCapacity(1);
        var delegate = mock(StreamingChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        var downstream = new RecordingHandler();
        var model = CapacityLimitedModels.streaming(delegate, capacity);
        capacity.tryAcquire("other");

        model.doChat(request, downstream);

        assertThat(downstream.error).isInstanceOf(LlmCallException.class);
        assertThat(((LlmCallException) downstream.error).getCode()).isEqualTo("LLM_BUSY");
        capacity.release();
    }

    private static final class RecordingHandler implements StreamingChatResponseHandler {
        private ChatResponse response;
        private Throwable error;
        @Override public void onPartialResponse(String text) { }
        @Override public void onCompleteResponse(ChatResponse response) { this.response = response; }
        @Override public void onError(Throwable error) { this.error = error; }
    }
}
