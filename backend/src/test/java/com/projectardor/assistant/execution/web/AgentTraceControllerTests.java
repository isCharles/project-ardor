package com.projectardor.assistant.execution.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.execution.service.AgentRunStore;
import com.projectardor.assistant.execution.service.AgentTraceStore;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

class AgentTraceControllerTests {

    private final AgentTraceStore store = mock(AgentTraceStore.class);
    private final AgentRunStore runs = mock(AgentRunStore.class);
    private final AgentTraceController controller = new AgentTraceController(store, runs);
    private final UUID owner = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final ArdorPrincipal principal = new ArdorPrincipal(owner, "trace@ardor.invalid", "", true, UserRole.USER);
    private final AgentTraceStore.SpanPage emptyPage =
            new AgentTraceStore.SpanPage(List.of(), 0, 0, 128, null);

    @Test
    void foreignOrUnknownRunReturnsNotFoundWithoutLeakingSpans() {
        when(runs.facts(owner, run)).thenReturn(null);

        assertThatThrownBy(() -> controller.trace(principal, run, 0, 128))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void aRunWithNoRecordedTraceStillReportsWhatItsOwnRowProves() {
        var started = Instant.parse("2026-11-01T10:00:00Z");
        var finished = started.plusMillis(9_500);
        when(store.findRoot(owner, run)).thenReturn(java.util.Optional.empty());
        when(store.findPage(owner, run, 0, 128)).thenReturn(emptyPage);
        when(runs.facts(owner, run)).thenReturn(new AgentRunStore.RunFacts(run, UUID.randomUUID(), "COMPLETED",
                started, finished, 3, 2, 2, 1_250L, 460L, null, null));

        var response = controller.trace(principal, run, 0, 128);

        assertThat(response.recordState()).isEqualTo("NOT_RECORDED");
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.durationMs()).isEqualTo(9_500);
        assertThat(response.totals().llmCalls()).isEqualTo(3);
        assertThat(response.totals().toolCalls()).isEqualTo(2);
        assertThat(response.totals().inputTokens()).isEqualTo(1_250L);
        assertThat(response.totals().source()).isEqualTo("RUN_RECORD");
        // The per-purpose split only exists in the spans, so those stay unknown rather than zero.
        assertThat(response.totals().requestedToolCalls()).isEqualTo(-1);
        assertThat(response.totals().complete()).isFalse();
    }

    @Test
    void aRunningRunWithNoSpansYetIsStillPreparing() {
        when(store.findRoot(owner, run)).thenReturn(java.util.Optional.empty());
        when(store.findPage(owner, run, 0, 128)).thenReturn(emptyPage);
        when(runs.facts(owner, run)).thenReturn(new AgentRunStore.RunFacts(run, UUID.randomUUID(), "RUNNING",
                Instant.now(), null, 0, 0, 0, null, null, null, null));

        var response = controller.trace(principal, run, 0, 128);

        assertThat(response.recordState()).isEqualTo("PREPARING");
        // No end yet means no duration to state; an absent figure is shown as unknown, never as instant.
        assertThat(response.durationMs()).isZero();
        assertThat(response.totals().inputTokens()).isZero();
    }

    @Test
    void ownerReadsAggregatedTrace() {
        var span = new AgentTraceStore.Span(UUID.randomUUID(), run, null, "RUN", "agent.run",
                "COMPLETED", Instant.now(), Instant.now(), 1234L, "fixture", "v1", 10, 5, null,
                Map.of("llmCalls", 1));
        UUID conversationId = UUID.randomUUID();
        when(store.findRoot(owner, run)).thenReturn(java.util.Optional.of(span));
        when(store.findPage(owner, run, 0, 128)).thenReturn(new AgentTraceStore.SpanPage(List.of(span), 1, 0, 128, null));
        when(runs.facts(owner, run)).thenReturn(new AgentRunStore.RunFacts(run, conversationId, "COMPLETED",
                Instant.now(), Instant.now(), 1, 0, 0, 10L, 5L, null, null));

        var response = controller.trace(principal, run, 0, 128);

        assertThat(response.runId()).isEqualTo(run);
        assertThat(response.conversationId()).isEqualTo(conversationId);
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.totals().llmCalls()).isEqualTo(1);
        assertThat(response.totals().source()).isEqualTo("RUN_SUMMARY");
        assertThat(response.truncated()).isFalse();
        assertThat(response.recordState()).isEqualTo("RECORDED");
        assertThat(response.spans()).hasSize(1);
    }

    @Test
    void pagedReadExposesTruncationAndNextOffset() {
        var root = new AgentTraceStore.Span(UUID.randomUUID(), run, null, "RUN", "agent.run",
                "COMPLETED", Instant.now(), Instant.now(), 1234L, "fixture", "v1", 10, 5, null,
                Map.of("llmCalls", 3, "toolCalls", 2, "loopRounds", 2));
        var llm = new AgentTraceStore.Span(UUID.randomUUID(), run, root.id(), "LLM", "llm.call.1",
                "COMPLETED", Instant.now(), Instant.now(), 100L, "fixture", "v1", 10, 5, null,
                Map.of("purpose", "ASSISTANT"));
        when(store.findRoot(owner, run)).thenReturn(java.util.Optional.of(root));
        when(store.findPage(owner, run, 0, 1)).thenReturn(new AgentTraceStore.SpanPage(List.of(llm), 6, 0, 1, 1));
        when(runs.facts(owner, run)).thenReturn(new AgentRunStore.RunFacts(run, UUID.randomUUID(), "COMPLETED",
                Instant.now(), Instant.now(), 3, 2, 2, 10L, 5L, null, null));

        var response = controller.trace(principal, run, 0, 1);

        assertThat(response.truncated()).isTrue();
        assertThat(response.nextOffset()).isEqualTo(1);
        assertThat(response.returnedSpans()).isEqualTo(1);
        assertThat(response.totalSpans()).isEqualTo(6);
        assertThat(response.totals().llmCalls()).isEqualTo(3);
        assertThat(response.totals().complete()).isTrue();
    }
}
