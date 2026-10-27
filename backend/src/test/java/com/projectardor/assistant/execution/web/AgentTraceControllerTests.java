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

    @Test
    void foreignOrUnknownRunReturnsNotFoundWithoutLeakingSpans() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        var controller = new AgentTraceController(store, runs);
        UUID owner = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        var principal = new ArdorPrincipal(owner, "trace@ardor.invalid", "", true, UserRole.USER);
        when(store.findRoot(owner, run)).thenReturn(java.util.Optional.empty());
        when(store.findPage(owner, run, 0, 128)).thenReturn(new AgentTraceStore.SpanPage(List.of(), 0, 0, 128, null));

        assertThatThrownBy(() -> controller.trace(principal, run, 0, 128))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void ownerReadsAggregatedTrace() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        var controller = new AgentTraceController(store, runs);
        UUID owner = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        var principal = new ArdorPrincipal(owner, "trace@ardor.invalid", "", true, UserRole.USER);
        var span = new AgentTraceStore.Span(UUID.randomUUID(), run, null, "RUN", "agent.run",
                "COMPLETED", Instant.now(), Instant.now(), 1234L, "fixture", "v1", 10, 5, null,
                Map.of("llmCalls", 1));
        when(store.findRoot(owner, run)).thenReturn(java.util.Optional.of(span));
        when(store.findPage(owner, run, 0, 128)).thenReturn(new AgentTraceStore.SpanPage(List.of(span), 1, 0, 128, null));
        UUID conversationId = UUID.randomUUID();
        when(runs.get(owner, run)).thenReturn(new AgentRunStore.AgentRun(run, conversationId, "message", "COMPLETED",
                "done", null, null, false, false, null, Instant.now(), Instant.now()));

        var response = controller.trace(principal, run, 0, 128);

        assertThat(response.runId()).isEqualTo(run);
        assertThat(response.conversationId()).isEqualTo(conversationId);
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.totals().llmCalls()).isEqualTo(1);
        assertThat(response.totals().source()).isEqualTo("RUN_SUMMARY");
        assertThat(response.truncated()).isFalse();
        assertThat(response.spans()).hasSize(1);
    }

    @Test
    void pagedReadExposesTruncationAndNextOffset() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        AgentRunStore runs = mock(AgentRunStore.class);
        var controller = new AgentTraceController(store, runs);
        UUID owner = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        var principal = new ArdorPrincipal(owner, "trace@ardor.invalid", "", true, UserRole.USER);
        var root = new AgentTraceStore.Span(UUID.randomUUID(), run, null, "RUN", "agent.run",
                "COMPLETED", Instant.now(), Instant.now(), 1234L, "fixture", "v1", 10, 5, null,
                Map.of("llmCalls", 3, "toolCalls", 2, "loopRounds", 2));
        var llm = new AgentTraceStore.Span(UUID.randomUUID(), run, root.id(), "LLM", "llm.call.1",
                "COMPLETED", Instant.now(), Instant.now(), 100L, "fixture", "v1", 10, 5, null,
                Map.of("purpose", "ASSISTANT"));
        when(store.findRoot(owner, run)).thenReturn(java.util.Optional.of(root));
        when(store.findPage(owner, run, 0, 1)).thenReturn(new AgentTraceStore.SpanPage(List.of(llm), 6, 0, 1, 1));
        when(runs.get(owner, run)).thenReturn(new AgentRunStore.AgentRun(run, UUID.randomUUID(), "message", "COMPLETED",
                "done", null, null, false, false, null, Instant.now(), Instant.now()));

        var response = controller.trace(principal, run, 0, 1);

        assertThat(response.truncated()).isTrue();
        assertThat(response.nextOffset()).isEqualTo(1);
        assertThat(response.returnedSpans()).isEqualTo(1);
        assertThat(response.totalSpans()).isEqualTo(6);
        assertThat(response.totals().llmCalls()).isEqualTo(3);
        assertThat(response.totals().complete()).isTrue();
    }
}

