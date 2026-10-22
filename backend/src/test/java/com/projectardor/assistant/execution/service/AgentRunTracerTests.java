package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AgentRunTracerTests {

    @Test
    void aggregatesActualLlmAndToolCallsWithoutUsingConfiguredRoundCaps() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        when(store.enabled()).thenReturn(true);
        UUID user = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        UUID llmSpan = UUID.randomUUID();
        UUID toolSpan = UUID.randomUUID();
        when(store.startSpan(eq(user), eq(run), isNull(), eq("RUN"), eq("agent.run"), any()))
                .thenReturn(root);
        when(store.startSpan(eq(user), eq(run), eq(root), eq("LLM"), eq("llm.call.1"), any()))
                .thenReturn(llmSpan);
        when(store.startSpan(eq(user), eq(run), eq(root), eq("TOOL"), eq("list_calendars"), any()))
                .thenReturn(toolSpan);

        AgentRunTracer tracer = AgentRunTracer.begin(store, user, run, conversation, "fixture-model",
                AgentRunTracer.PROMPT_VERSION);
        UUID actualLlmSpan = tracer.startChild("LLM", "llm.call.1", Map.of());
        tracer.finishChild(actualLlmSpan, "COMPLETED", null, "fixture-model", 120, 40, Map.of("toolRound", true));
        tracer.recordLlm(120, 40, true);
        UUID actualToolSpan = tracer.startChild("TOOL", "list_calendars", Map.of());
        tracer.recordTool();
        tracer.finishChild(actualToolSpan, "COMPLETED", null, null, null, null, Map.of());
        tracer.complete();

        assertThat(tracer.llmCalls()).isEqualTo(1);
        assertThat(tracer.toolCalls()).isEqualTo(1);
        assertThat(tracer.loopRounds()).isEqualTo(1);
        assertThat(tracer.closed()).isTrue();
        verify(store).finishSpan(eq(user), eq(root), eq("COMPLETED"), isNull(), eq("fixture-model"),
                eq(AgentRunTracer.PROMPT_VERSION), eq(120), eq(40), any());
        verify(store).updateRunAggregates(user, run, 1, 1, 1, 120, 40,
                "fixture-model", AgentRunTracer.PROMPT_VERSION);
    }

    @Test
    void failedRunStillUpdatesAggregatesAndClosesOnce() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        when(store.enabled()).thenReturn(true);
        UUID user = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        when(store.startSpan(eq(user), eq(run), isNull(), eq("RUN"), eq("agent.run"), any())).thenReturn(root);

        AgentRunTracer tracer = AgentRunTracer.begin(store, user, run, null, "fixture-model", null);
        tracer.fail("LLM_BAD_REQUEST");
        tracer.fail("LLM_BAD_REQUEST");

        verify(store).finishSpan(eq(user), eq(root), eq("FAILED"), eq("LLM_BAD_REQUEST"), any(), any(),
                any(), any(), any());
        verify(store).updateRunAggregates(user, run, 0, 0, 0, 0, 0, "fixture-model",
                AgentRunTracer.PROMPT_VERSION);
    }
}
