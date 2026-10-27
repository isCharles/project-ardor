package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Real PostgreSQL aggregates, isolated to connection-local tables; no production rows are accessed. */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class QueueDepthMetricsPostgresTests {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach void prepare() throws Exception {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        // Dedicated acceptance credentials only. Do not consume arbitrary application credentials.
        var connection = DriverManager.getConnection(url, "ardor", "acceptance-only");
        dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TEMP TABLE agent_message_queue(id uuid, status text, created_at timestamptz)");
        jdbc.execute("CREATE TEMP TABLE ai_jobs(id uuid, status text, priority integer, created_at timestamptz)");
    }

    @AfterEach void close() {
        if (dataSource != null) dataSource.destroy();
    }

    @Test void samplesDepthAndOldestAgeFromPostgres() {
        jdbc.update("INSERT INTO agent_message_queue VALUES (?, 'QUEUED', CURRENT_TIMESTAMP - INTERVAL '10 seconds')",
                UUID.randomUUID());
        jdbc.update("INSERT INTO agent_message_queue VALUES (?, 'RUNNING', CURRENT_TIMESTAMP)", UUID.randomUUID());
        jdbc.update("INSERT INTO ai_jobs VALUES (?, 'QUEUED', 0, CURRENT_TIMESTAMP - INTERVAL '3 seconds')",
                UUID.randomUUID());
        jdbc.update("INSERT INTO ai_jobs VALUES (?, 'QUEUED', 1, CURRENT_TIMESTAMP - INTERVAL '90 seconds')",
                UUID.randomUUID());

        var registry = new SimpleMeterRegistry();
        new QueueDepthMetrics(jdbc, registry).sample();

        assertThat(registry.get("ardor.agent.queue.depth").tag("status", "QUEUED").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("ardor.agent.queue.depth").tag("status", "RUNNING").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("ardor.agent.queue.oldest.queued.seconds").gauge().value())
                .isBetween(9.0, 120.0);
        assertThat(registry.get("ardor.ai.jobs.depth").tag("status", "QUEUED").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("ardor.ai.jobs.oldest.queued.seconds").tag("priority", "0").gauge().value())
                .isBetween(2.0, 60.0);
        assertThat(registry.get("ardor.ai.jobs.oldest.queued.seconds").tag("priority", "1").gauge().value())
                .isBetween(89.0, 180.0);
    }

    @Test void emptyQueueReportsZeroOldestAge() {
        var registry = new SimpleMeterRegistry();
        new QueueDepthMetrics(jdbc, registry).sample();

        assertThat(registry.get("ardor.agent.queue.oldest.queued.seconds").gauge().value()).isEqualTo(0.0);
        assertThat(registry.find("ardor.agent.queue.depth").gauges()).isEmpty();
    }
}
