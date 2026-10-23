package com.projectardor.assistant.execution.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AgentTraceResponseTests {

    @Test
    void aggregatesOnlyLlmTokenCountsAndUsesActualSpans() {
        UUID run = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        var rootSpan = span(root, null, "RUN", "agent.run", "COMPLETED", 9000L, 50_000, 300,
                Map.of("llmCalls", 2));
        var firstLlm = span(UUID.randomUUID(), root, "LLM", "llm.call.1", "COMPLETED", 2000L, 1000, 50,
                Map.of("toolRound", true));
        var tool = span(UUID.randomUUID(), root, "TOOL", "list_resumes", "COMPLETED", 40L, null, null, Map.of());
        var secondLlm = span(UUID.randomUUID(), root, "LLM", "llm.call.2", "COMPLETED", 3000L, 1200, 80,
                Map.of("toolRound", false));

        UUID conversationId = UUID.randomUUID();
        var response = AgentTraceResponse.from(run, conversationId, List.of(rootSpan, firstLlm, tool, secondLlm).stream()
                .map(AgentTraceSpanResponse::from).toList());

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.durationMs()).isEqualTo(9000);
        assertThat(response.totals().llmCalls()).isEqualTo(2);
        assertThat(response.totals().toolCalls()).isEqualTo(1);
        assertThat(response.totals().loopRounds()).isEqualTo(1);
        assertThat(response.totals().inputTokens()).isEqualTo(2200);
        assertThat(response.totals().outputTokens()).isEqualTo(130);
    }

    private static com.projectardor.assistant.execution.service.AgentTraceStore.Span span(
            UUID id, UUID parent, String kind, String name, String status, Long duration,
            Integer input, Integer output, Map<String, Object> attributes) {
        return new com.projectardor.assistant.execution.service.AgentTraceStore.Span(id, null, parent, kind, name,
                status, Instant.now(), Instant.now(), duration, "fixture-model", "v1", input, output, null,
                attributes);
    }
}

