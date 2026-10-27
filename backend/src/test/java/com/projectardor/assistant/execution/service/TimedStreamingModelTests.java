package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;

class TimedStreamingModelTests {
    @Test void measuresFirstContentAndRequestRateWithoutDuplicateTraceCallbacks() {
        Fixture f = new Fixture();
        f.start();
        f.clock.set(200_000_000); f.calls.getFirst().onPartialResponse("first");
        f.clock.set(400_000_000); f.calls.getFirst().onPartialResponse("second");
        f.clock.set(1_000_000_000); f.calls.getFirst().onCompleteResponse(response());
        Map<String, Object> timing = f.finished().getFirst();
        assertThat(timing).containsEntry("firstContentMs", 200.0).containsEntry("streamAfterFirstContentMs", 800.0)
                .containsEntry("requestDurationMs", 1000.0).containsEntry("outputTokensPerRequestSecond", 80.0);
        assertThat(f.tracer.llmCalls()).isEqualTo(1);
        verify(f.downstream).onPartialResponse("first");
        verify(f.downstream).onCompleteResponse(any());
    }

    @Test void missingPartialEventsStayUnknownInsteadOfUsingTotalDuration() {
        Fixture f = new Fixture(); f.start(); f.clock.set(1_000_000_000);
        f.calls.getFirst().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
        assertThat(f.finished().getFirst()).containsEntry("requestDurationMs", 1000.0)
                .doesNotContainKeys("firstContentMs", "streamAfterFirstContentMs", "outputTokensPerRequestSecond");
    }

    @Test void overlappingCallsHaveIndependentClocksAndPreserveCancellationContext() {
        Fixture f = new Fixture(); f.start();
        f.clock.set(100_000_000); f.start();
        PartialResponse part = mock(PartialResponse.class);
        PartialResponseContext context = mock(PartialResponseContext.class);
        when(part.text()).thenReturn("chunk");
        f.clock.set(300_000_000); f.calls.get(1).onPartialResponse(part, context);
        f.clock.set(500_000_000); f.calls.get(1).onCompleteResponse(response());
        f.clock.set(700_000_000); f.calls.getFirst().onPartialResponse("late");
        f.clock.set(900_000_000); f.calls.getFirst().onCompleteResponse(response());
        assertThat(f.finished().get(0)).containsEntry("firstContentMs", 200.0).containsEntry("requestDurationMs", 400.0);
        assertThat(f.finished().get(1)).containsEntry("firstContentMs", 700.0).containsEntry("requestDurationMs", 900.0);
        verify(f.downstream).onPartialResponse(same(part), same(context));
    }

    @Test void errorsRetainObservedTimingWithoutInventingUsageAndOnlyFinishOnce() {
        Fixture f = new Fixture(); f.start();
        f.clock.set(100_000_000); f.calls.getFirst().onPartialThinking(mock(PartialThinking.class));
        f.clock.set(200_000_000); f.calls.getFirst().onPartialResponse("partial");
        f.clock.set(500_000_000); var error = new IllegalStateException("synthetic");
        f.calls.getFirst().onError(error); f.calls.getFirst().onCompleteResponse(response());
        assertThat(f.finished()).singleElement().satisfies(timing -> assertThat(timing)
                .containsEntry("firstContentMs", 200.0).containsEntry("requestDurationMs", 500.0)
                .doesNotContainKey("outputTokensPerRequestSecond"));
        verify(f.downstream).onError(same(error)); verify(f.downstream, never()).onCompleteResponse(any());
    }

    private static ChatResponse response() { return ChatResponse.builder().aiMessage(AiMessage.from("done")).tokenUsage(new TokenUsage(10, 80)).build(); }

    private static class Fixture {
        final AtomicLong clock = new AtomicLong();
        final AgentTraceStore store = mock(AgentTraceStore.class);
        final StreamingChatModel delegate = mock(StreamingChatModel.class);
        final StreamingChatResponseHandler downstream = mock(StreamingChatResponseHandler.class);
        final List<StreamingChatResponseHandler> calls = new ArrayList<>();
        final AgentRunTracer tracer;
        final TimedStreamingModel model;
        Fixture() {
            when(store.enabled()).thenReturn(true);
            when(store.startSpan(any(), any(), any(), any(), any(), any())).thenAnswer(call -> UUID.randomUUID());
            tracer = AgentRunTracer.begin(store, UUID.randomUUID(), UUID.randomUUID(), null, "synthetic", "test");
            when(delegate.listeners()).thenReturn(List.of(new AgentModelTraceListener(tracer, "synthetic")));
            doAnswer(call -> { calls.add(call.getArgument(1)); return null; }).when(delegate).doChat(any(), any());
            model = new TimedStreamingModel(delegate, clock::get);
        }
        void start() { model.doChat(ChatRequest.builder().messages(UserMessage.from("synthetic")).build(), downstream); }
        @SuppressWarnings({"unchecked", "rawtypes"}) List<Map<String, Object>> finished() {
            ArgumentCaptor<Map> captured = ArgumentCaptor.forClass(Map.class);
            verify(store, atLeastOnce()).finishSpan(any(), any(), any(), any(), any(), any(), any(), any(), captured.capture());
            return (List) captured.getAllValues();
        }
    }
}
