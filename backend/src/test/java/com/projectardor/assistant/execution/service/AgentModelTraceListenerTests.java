package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

class AgentModelTraceListenerTests {
    @Test
    void compactionHasExplicitPurposeAndContextParentWithRunWideCallNumbers() {
        var store = mock(AgentTraceStore.class);
        when(store.enabled()).thenReturn(true);
        when(store.startSpan(any(), any(), any(), any(), any(), any())).thenAnswer(call -> UUID.randomUUID());
        UUID user = UUID.randomUUID(), run = UUID.randomUUID();
        var tracer = AgentRunTracer.begin(store, user, run, null, "fixture", "v1");
        UUID contextSpan = tracer.startChild("CONTEXT", "context.load", Map.of());
        var compact = new AgentModelTraceListener(tracer, "fixture", "CONTEXT_COMPACTION", tracer.contextSpanId());
        var reply = new AgentModelTraceListener(tracer, "fixture");
        var request = mock(ChatModelRequestContext.class);
        when(request.attributes()).thenAnswer(call -> new HashMap<>());
        compact.onRequest(request);
        tracer.finishChild(contextSpan, "COMPLETED", null, "fixture", null, null, Map.of());
        assertThat(tracer.contextSpanId()).isNull();
        reply.onRequest(request);
        verify(store).startSpan(eq(user), eq(run), eq(contextSpan), eq("LLM"), eq("context.compact.1"),
                org.mockito.ArgumentMatchers.argThat(attributes -> "CONTEXT_COMPACTION".equals(attributes.get("purpose"))));
        verify(store).startSpan(eq(user), eq(run), eq(tracer.rootSpanId()), eq("LLM"), eq("llm.call.2"),
                org.mockito.ArgumentMatchers.argThat(attributes -> "ASSISTANT".equals(attributes.get("purpose"))));
    }

    @Test
    void responseTokensAreCountedEvenWhenNoTraceSpanCanBeCreated() {
        AgentRunTracer tracer = AgentRunTracer.begin(null, UUID.randomUUID(), UUID.randomUUID(), null,
                "fixture", null);
        AgentModelTraceListener listener = new AgentModelTraceListener(tracer, "fixture");
        ChatModelResponseContext context = mock(ChatModelResponseContext.class);
        when(context.attributes()).thenReturn(new HashMap<>());
        when(context.chatResponse()).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("synthetic"))
                .tokenUsage(new TokenUsage(11, 7)).build());
        listener.onResponse(context);
        tracer.complete();
        assertThat(tracer.inputTokens()).isEqualTo(11);
        assertThat(tracer.outputTokens()).isEqualTo(7);
        assertThat(tracer.closed()).isTrue();
    }


    @Test
    void recordsOneLlmSpanWithModelTokensAndToolRound() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        when(store.enabled()).thenReturn(true);
        UUID user = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        UUID llmSpan = UUID.randomUUID();
        when(store.startSpan(eq(user), eq(run), isNull(), eq("RUN"), eq("agent.run"), any())).thenReturn(root);
        AgentRunTracer tracer = AgentRunTracer.begin(store, user, run, null, "configured-model", "v1");
        // The root span is created by begin(); give the listener its child id.
        when(store.startSpan(eq(user), eq(run), eq(tracer.rootSpanId()), eq("LLM"), eq("llm.call.1"), any()))
                .thenReturn(llmSpan);
        AgentModelTraceListener listener = new AgentModelTraceListener(tracer, "configured-model");
        Map<Object, Object> attributes = new HashMap<>();
        ChatModelRequestContext request = mock(ChatModelRequestContext.class);
        when(request.attributes()).thenReturn(attributes);
        when(request.chatRequest()).thenReturn(null);
        when(request.modelProvider()).thenReturn(null);
        listener.onRequest(request);

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder().id("call-1")
                        .name("list_calendars").arguments("{}").build())))
                .modelName("actual-model")
                .tokenUsage(new TokenUsage(100, 25))
                .build();
        ChatModelResponseContext responseContext = mock(ChatModelResponseContext.class);
        when(responseContext.attributes()).thenReturn(attributes);
        when(responseContext.chatResponse()).thenReturn(response);
        listener.onResponse(responseContext);

        assertThat(tracer.llmCalls()).isEqualTo(1);
        assertThat(tracer.loopRounds()).isEqualTo(1);
        verify(store).finishSpan(eq(user), eq(llmSpan), eq("COMPLETED"), isNull(), eq("actual-model"),
                eq("v1"), eq(100), eq(25), any());
    }
}
