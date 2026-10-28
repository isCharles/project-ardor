package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class QueueDepthMetricsTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void samplePublishesDepthWaitAgeAndPriorityStarvationGauges() {
        when(jdbc.queryForList(QueueDepthMetrics.AGENT_DEPTH_SQL)).thenReturn(List.of(
                Map.of("status", "QUEUED", "n", 2L),
                Map.of("status", "RUNNING", "n", 1L)));
        when(jdbc.queryForList(QueueDepthMetrics.AGENT_OLDEST_SQL)).thenReturn(List.of(Map.of("age", 42.5)));
        when(jdbc.queryForList(QueueDepthMetrics.AGENT_PRIORITY_DEPTH_SQL)).thenReturn(List.of(
                Map.of("status", "QUEUED", "priority", 0, "n", 2L),
                Map.of("status", "QUEUED", "priority", 1, "n", 1L)));
        when(jdbc.queryForList(QueueDepthMetrics.AGENT_PRIORITY_OLDEST_SQL)).thenReturn(List.of(
                Map.of("priority", 0, "age", 75.0),
                Map.of("priority", 1, "age", 5.0)));
        when(jdbc.queryForList(QueueDepthMetrics.JOB_DEPTH_SQL)).thenReturn(List.of(
                Map.of("status", "QUEUED", "n", 5L)));
        when(jdbc.queryForList(QueueDepthMetrics.JOB_OLDEST_SQL)).thenReturn(List.of(
                Map.of("priority", 1, "age", 120.0),
                Map.of("priority", 0, "age", 8.0)));

        new QueueDepthMetrics(jdbc, registry).sample();

        assertThat(registry.get("ardor.agent.queue.depth").tag("status", "QUEUED").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("ardor.agent.queue.depth").tag("status", "RUNNING").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("ardor.agent.queue.oldest.queued.seconds").gauge().value()).isEqualTo(42.5);
        assertThat(registry.get("ardor.agent.queue.priority.depth")
                .tags("status", "QUEUED", "priority", "0").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("ardor.agent.queue.priority.depth")
                .tags("status", "QUEUED", "priority", "1").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("ardor.agent.queue.priority.oldest.queued.seconds")
                .tag("priority", "0").gauge().value()).isEqualTo(75.0);
        assertThat(registry.get("ardor.agent.queue.priority.oldest.queued.seconds")
                .tag("priority", "1").gauge().value()).isEqualTo(5.0);
        assertThat(registry.get("ardor.ai.jobs.depth").tag("status", "QUEUED").gauge().value()).isEqualTo(5.0);
        assertThat(registry.get("ardor.ai.jobs.oldest.queued.seconds").tag("priority", "1").gauge().value())
                .isEqualTo(120.0);
        assertThat(registry.get("ardor.ai.jobs.oldest.queued.seconds").tag("priority", "0").gauge().value())
                .isEqualTo(8.0);
    }

    @Test
    void emptyQueuesReportZeroAge() {
        when(jdbc.queryForList(QueueDepthMetrics.AGENT_DEPTH_SQL)).thenReturn(List.of());
        when(jdbc.queryForList(QueueDepthMetrics.AGENT_OLDEST_SQL)).thenReturn(List.of(Map.of("age", 0L)));
        when(jdbc.queryForList(QueueDepthMetrics.JOB_DEPTH_SQL)).thenReturn(List.of());
        when(jdbc.queryForList(QueueDepthMetrics.JOB_OLDEST_SQL)).thenReturn(List.of());

        new QueueDepthMetrics(jdbc, registry).sample();

        assertThat(registry.get("ardor.agent.queue.oldest.queued.seconds").gauge().value()).isEqualTo(0.0);
        assertThat(registry.find("ardor.agent.queue.depth").gauges()).isEmpty();
        assertThat(registry.find("ardor.ai.jobs.oldest.queued.seconds").gauges()).isEmpty();
    }

    @Test
    void databaseFailuresNeverEscapeTheSampler() {
        when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> new QueueDepthMetrics(jdbc, registry).sample()).doesNotThrowAnyException();
    }
}
