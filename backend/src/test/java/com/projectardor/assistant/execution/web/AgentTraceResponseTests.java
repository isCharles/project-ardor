package com.projectardor.assistant.execution.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.execution.service.AgentTraceStore;

class AgentTraceResponseTests {

    @Test
    void runSummaryCarriesFullRunCountsEvenWhenThePageIsShort() {
        UUID run = UUID.randomUUID();
        var root = span(UUID.randomUUID(), null, "RUN", "agent.run", "COMPLETED", 9000L, 50_000, 300,
                Map.of("llmCalls", 2, "assistantCalls", 1, "summaryCalls", 1,
                        "toolCalls", 1, "requestedToolCalls", 2, "executedToolCalls", 1,
                        "loopRounds", 1, "usageMissingCalls", 0));
        var firstLlm = span(UUID.randomUUID(), root.id(), "LLM", "llm.call.1", "COMPLETED", 2000L, 1000, 50,
                Map.of("toolRound", true, "purpose", "ASSISTANT", "requestedTools", List.of("list_resumes")));
        var tool = span(UUID.randomUUID(), root.id(), "TOOL", "list_resumes", "COMPLETED", 40L, null, null,
                Map.of("executed", true));
        var secondLlm = span(UUID.randomUUID(), root.id(), "LLM", "llm.call.2", "COMPLETED", 3000L, 1200, 80,
                Map.of("toolRound", false, "purpose", "ASSISTANT"));

        var response = AgentTraceResponse.from(run, UUID.randomUUID(), root, List.of(firstLlm, tool, secondLlm),
                3, null);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.durationMs()).isEqualTo(9000);
        assertThat(response.totals().source()).isEqualTo("RUN_SUMMARY");
        assertThat(response.totals().complete()).isTrue();
        assertThat(response.totals().llmCalls()).isEqualTo(2);
        assertThat(response.totals().assistantCalls()).isEqualTo(1);
        assertThat(response.totals().summaryCalls()).isEqualTo(1);
        assertThat(response.totals().toolCalls()).isEqualTo(1);
        assertThat(response.totals().requestedToolCalls()).isEqualTo(2);
        assertThat(response.totals().executedToolCalls()).isEqualTo(1);
        assertThat(response.totals().loopRounds()).isEqualTo(1);
        assertThat(response.totals().inputTokens()).isEqualTo(50_000);
        assertThat(response.totals().outputTokens()).isEqualTo(300);
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void pagedSpansWithoutRunSummaryAreMarkedIncomplete() {
        UUID run = UUID.randomUUID();
        var root = span(UUID.randomUUID(), null, "RUN", "agent.run", "RUNNING", null, null, null, Map.of());
        List<AgentTraceSpanResponse> page = new ArrayList<>();
        page.add(root);
        for (int i = 0; i < 127; i++) {
            page.add(span(UUID.randomUUID(), root.id(), "LLM", "llm.call." + i, "COMPLETED", 10L, 100, 10,
                    Map.of("purpose", "ASSISTANT")));
        }

        var response = AgentTraceResponse.from(run, UUID.randomUUID(), root, page, 130, 128);

        assertThat(response.truncated()).isTrue();
        assertThat(response.nextOffset()).isEqualTo(128);
        assertThat(response.returnedSpans()).isEqualTo(128);
        assertThat(response.totalSpans()).isEqualTo(130);
        assertThat(response.totals().source()).isEqualTo("SPANS_PARTIAL");
        assertThat(response.totals().complete()).isFalse();
        assertThat(response.totals().llmCalls()).isEqualTo(127);
        assertThat(response.totals().assistantCalls()).isEqualTo(127);
    }

    @Test
    void runSummaryKeepsTotalsCompleteWhenSpansArePaged() {
        UUID run = UUID.randomUUID();
        var root = span(UUID.randomUUID(), null, "RUN", "agent.run", "COMPLETED", 90_000L, 900_000, 80_000,
                Map.of("llmCalls", 200, "assistantCalls", 199, "summaryCalls", 1,
                        "toolCalls", 150, "requestedToolCalls", 160, "executedToolCalls", 145,
                        "loopRounds", 100, "usageMissingCalls", 2));
        var firstLlm = span(UUID.randomUUID(), root.id(), "LLM", "llm.call.1", "COMPLETED", 100L, 100, 10,
                Map.of("purpose", "ASSISTANT"));

        var response = AgentTraceResponse.from(run, UUID.randomUUID(), root, List.of(firstLlm), 400, 128);

        assertThat(response.truncated()).isTrue();
        assertThat(response.totals().source()).isEqualTo("RUN_SUMMARY");
        assertThat(response.totals().complete()).isTrue();
        assertThat(response.totals().llmCalls()).isEqualTo(200);
        assertThat(response.totals().toolCalls()).isEqualTo(150);
        assertThat(response.totals().executedToolCalls()).isEqualTo(145);
        assertThat(response.totals().usageMissingCalls()).isEqualTo(2);
        assertThat(response.totals().inputTokens()).isEqualTo(900_000);
    }

    @Test
    void missingUsageStaysVisibleInsteadOfBecomingZeroUsageCalls() {
        UUID run = UUID.randomUUID();
        var root = span(UUID.randomUUID(), null, "RUN", "agent.run", "RUNNING", null, null, null, Map.of());
        var answered = span(UUID.randomUUID(), root.id(), "LLM", "llm.call.1", "COMPLETED", 10L, 100, 10,
                Map.of("purpose", "ASSISTANT"));
        var missing = span(UUID.randomUUID(), root.id(), "LLM", "llm.call.2", "FAILED", 10L, null, null,
                Map.of("purpose", "ASSISTANT"));

        var response = AgentTraceResponse.from(run, UUID.randomUUID(), root, List.of(answered, missing), 3, null);

        assertThat(response.totals().source()).isEqualTo("SPANS");
        assertThat(response.totals().complete()).isTrue();
        assertThat(response.totals().llmCalls()).isEqualTo(2);
        assertThat(response.totals().usageMissingCalls()).isEqualTo(1);
        assertThat(response.totals().inputTokens()).isEqualTo(100);
    }

    private static AgentTraceSpanResponse span(UUID id, UUID parent, String kind, String name, String status,
            Long duration, Integer input, Integer output, Map<String, Object> attributes) {
        return AgentTraceSpanResponse.from(new AgentTraceStore.Span(id, null, parent, kind, name, status,
                Instant.now(), Instant.now(), duration, "fixture-model", "v1", input, output, null, attributes));
    }
}
