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
        when(store.find(owner, run)).thenReturn(List.of());

        assertThatThrownBy(() -> controller.trace(principal, run))
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
        when(store.find(owner, run)).thenReturn(List.of(span));
        UUID conversationId = UUID.randomUUID();
        when(runs.get(owner, run)).thenReturn(new AgentRunStore.AgentRun(run, conversationId, "message", "COMPLETED",
                "done", null, null, false, false, null, Instant.now(), Instant.now()));

        var response = controller.trace(principal, run);

        assertThat(response.runId()).isEqualTo(run);
        assertThat(response.conversationId()).isEqualTo(conversationId);
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.totals().llmCalls()).isZero();
        assertThat(response.spans()).hasSize(1);
    }
}

