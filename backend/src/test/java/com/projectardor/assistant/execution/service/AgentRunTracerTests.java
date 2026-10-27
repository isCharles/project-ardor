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
    void disablingTracePersistenceDoesNotDisableRunTokenTotals() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        AgentRunTracer tracer = AgentRunTracer.begin(store, UUID.randomUUID(), UUID.randomUUID(), null, "fixture", null);
        tracer.recordLlm(120, 40, true);
        tracer.recordLlm(5, 6, false);
        tracer.recordLlm(-10, -20, false);
        tracer.recordTool();
        tracer.complete();
        tracer.recordLlm(999, 999, true);
        assertThat(tracer.inputTokens()).isEqualTo(125);
        assertThat(tracer.outputTokens()).isEqualTo(46);
        assertThat(tracer.llmCalls()).isEqualTo(3);
        assertThat(tracer.toolCalls()).isEqualTo(1);
        assertThat(tracer.closed()).isTrue();
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).finishSpan(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void separatesAssistantSummaryToolAndMissingUsageCounters() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        AgentRunTracer tracer = AgentRunTracer.begin(store, UUID.randomUUID(), UUID.randomUUID(), null, "fixture", null);
        tracer.recordLlm("CONTEXT_COMPACTION", 10, 5, false, 0);
        tracer.recordLlm("ASSISTANT", 120, 40, true, 2);
        tracer.recordLlm("ASSISTANT", null, 12, false, 0);
        tracer.recordTool();
        tracer.recordTool();
        tracer.recordToolExecuted();
        tracer.complete();
        assertThat(tracer.llmCalls()).isEqualTo(3);
        assertThat(tracer.assistantCalls()).isEqualTo(2);
        assertThat(tracer.summaryCalls()).isEqualTo(1);
        assertThat(tracer.requestedToolCalls()).isEqualTo(2);
        assertThat(tracer.toolCalls()).isEqualTo(2);
        assertThat(tracer.executedToolCalls()).isEqualTo(1);
        assertThat(tracer.usageMissingCalls()).isEqualTo(1);
    }

    @Test
    void missingRootSpanDoesNotLoseUsageOrKeepAnEndedRunOpen() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        when(store.enabled()).thenReturn(true);
        AgentRunTracer tracer = AgentRunTracer.begin(store, UUID.randomUUID(), UUID.randomUUID(), null, "fixture", null);
        tracer.recordLlm(7, 9, false);
        tracer.fail("fixture");
        tracer.complete();
        assertThat(tracer.inputTokens()).isEqualTo(7);
        assertThat(tracer.outputTokens()).isEqualTo(9);
        assertThat(tracer.closed()).isTrue();
        assertThat(tracer.active()).isFalse();
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).finishSpan(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }


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
