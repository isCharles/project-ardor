package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentMetricsTests {

    private MeterRegistry registry;
    private AgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
    }

    @Test
    void activeRunsGaugeTracksStartAndEnd() {
        metrics.recordRunStart();
        metrics.recordRunStart();
        assertThat(registry.get("ardor.agent.run.active").gauge().value()).isEqualTo(2.0);

        metrics.recordRunEnd(Duration.ofMillis(100).toNanos(), true, null);
        assertThat(registry.get("ardor.agent.run.active").gauge().value()).isEqualTo(1.0);

        metrics.recordRunEnd(Duration.ofMillis(50).toNanos(), false, "AGENT_LLM_FAILED");
        assertThat(registry.get("ardor.agent.run.active").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void runSuccessAndErrorCounters() {
        metrics.recordRunEnd(Duration.ofMillis(10).toNanos(), true, null);
        metrics.recordRunEnd(Duration.ofMillis(20).toNanos(), false, "AGENT_STREAM_FAILED");

        assertThat(registry.get("ardor.agent.run.successes").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ardor.agent.run.errors").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ardor.agent.run.duration").timer().count()).isEqualTo(2);
    }

    @Test
    void llmCallRecordsDurationAndTokensByModel() {
        metrics.recordLlmCall(Duration.ofMillis(200).toNanos(), 150, 80, "gpt-4o", false);
        metrics.recordLlmCall(Duration.ofMillis(300).toNanos(), 200, 120, "gpt-4o", false);
        metrics.recordLlmCall(Duration.ofMillis(50).toNanos(), null, null, "claude-3", true);

        assertThat(registry.get("ardor.agent.llm.call.duration").tag("model", "gpt-4o").timer().count()).isEqualTo(2);
        assertThat(registry.get("ardor.agent.llm.call.duration").tag("model", "claude-3").timer().count()).isEqualTo(1);
        assertThat(registry.get("ardor.agent.llm.tokens.input").tag("model", "gpt-4o").counter().count()).isEqualTo(350.0);
        assertThat(registry.get("ardor.agent.llm.tokens.output").tag("model", "gpt-4o").counter().count()).isEqualTo(200.0);
        assertThat(registry.get("ardor.agent.llm.errors").tag("model", "claude-3").counter().count()).isEqualTo(1.0);
    }

    @Test
    void toolCallRecordsDurationAndErrorsByTool() {
        metrics.recordToolCall(Duration.ofMillis(50).toNanos(), "list_resumes", false);
        metrics.recordToolCall(Duration.ofMillis(100).toNanos(), "list_resumes", false);
        metrics.recordToolCall(Duration.ofMillis(30).toNanos(), "search_knowledge", true);

        assertThat(registry.get("ardor.agent.tool.call.duration").tag("tool", "list_resumes").timer().count()).isEqualTo(2);
        assertThat(registry.get("ardor.agent.tool.call.duration").tag("tool", "search_knowledge").timer().count()).isEqualTo(1);
        assertThat(registry.get("ardor.agent.tool.call.errors").tag("tool", "search_knowledge").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("ardor.agent.tool.call.errors").tag("tool", "list_resumes").counter()).isNull();
    }

    @Test
    void timeToFirstTokenIsRecorded() {
        metrics.recordTimeToFirstToken(Duration.ofMillis(450).toNanos());
        metrics.recordTimeToFirstToken(Duration.ofMillis(800).toNanos());

        assertThat(registry.get("ardor.agent.stream.ttft").timer().count()).isEqualTo(2);
    }
}
