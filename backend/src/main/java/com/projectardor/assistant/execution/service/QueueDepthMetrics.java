package com.projectardor.assistant.execution.service;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * Samples queue depth and oldest-wait age from PostgreSQL.
 *
 * <p>Gauges are refreshed on a schedule instead of on scrape so a slow or failed query cannot
 * block the metrics endpoint. The oldest QUEUED age per priority is the starvation signal for
 * both AI jobs and paid/free chat messages.</p>
 */
@Component
public class QueueDepthMetrics {
    static final String AGENT_DEPTH_SQL = """
            SELECT status, COUNT(*) AS n FROM agent_message_queue
            WHERE status IN ('QUEUED','DISPATCHING','RUNNING') GROUP BY status
            """;
    static final String AGENT_OLDEST_SQL = """
            SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))), 0) AS age
            FROM agent_message_queue WHERE status = 'QUEUED'
            """;
    static final String AGENT_PRIORITY_DEPTH_SQL = """
            SELECT status, priority, COUNT(*) AS n FROM agent_message_queue
            WHERE status IN ('QUEUED','DISPATCHING','RUNNING') GROUP BY status, priority
            """;
    static final String AGENT_PRIORITY_OLDEST_SQL = """
            SELECT priority, COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))), 0) AS age
            FROM agent_message_queue WHERE status = 'QUEUED' GROUP BY priority
            """;
    static final String JOB_DEPTH_SQL = """
            SELECT status, COUNT(*) AS n FROM ai_jobs
            WHERE status IN ('QUEUED','RUNNING') GROUP BY status
            """;
    static final String JOB_OLDEST_SQL = """
            SELECT priority, COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))), 0) AS age
            FROM ai_jobs WHERE status = 'QUEUED' GROUP BY priority
            """;

    private final JdbcTemplate jdbc;
    private final MultiGauge agentDepth;
    private final MultiGauge agentOldestQueued;
    private final MultiGauge agentPriorityDepth;
    private final MultiGauge agentPriorityOldestQueued;
    private final MultiGauge jobDepth;
    private final MultiGauge jobOldestQueued;

    public QueueDepthMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.agentDepth = MultiGauge.builder("ardor.agent.queue.depth")
                .description("Agent chat messages by current queue status")
                .register(registry);
        this.agentOldestQueued = MultiGauge.builder("ardor.agent.queue.oldest.queued.seconds")
                .description("Age of the oldest QUEUED agent chat message")
                .register(registry);
        this.agentPriorityDepth = MultiGauge.builder("ardor.agent.queue.priority.depth")
                .description("Agent chat messages by queue status and priority")
                .register(registry);
        this.agentPriorityOldestQueued = MultiGauge.builder("ardor.agent.queue.priority.oldest.queued.seconds")
                .description("Age of the oldest QUEUED agent chat message per priority; a growing free tier signals starvation")
                .register(registry);
        this.jobDepth = MultiGauge.builder("ardor.ai.jobs.depth")
                .description("AI jobs by current queue status")
                .register(registry);
        this.jobOldestQueued = MultiGauge.builder("ardor.ai.jobs.oldest.queued.seconds")
                .description("Age of the oldest QUEUED AI job per priority; a growing low tier signals starvation")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.observability.queue-sample-interval:15s}")
    public void sample() {
        try {
            // MultiGauge keeps the first value per tag set unless overwrite is enabled.
            agentDepth.register(rows(AGENT_DEPTH_SQL, "status", "n"), true);
            agentOldestQueued.register(List.of(MultiGauge.Row.of(Tags.empty(), scalar(AGENT_OLDEST_SQL))), true);
            agentPriorityDepth.register(jdbc.queryForList(AGENT_PRIORITY_DEPTH_SQL).stream()
                    .map(row -> MultiGauge.Row.of(Tags.of(
                            "status", String.valueOf(row.get("status")),
                            "priority", String.valueOf(row.get("priority"))), number(row.get("n"))))
                    .toList(), true);
            agentPriorityOldestQueued.register(rows(AGENT_PRIORITY_OLDEST_SQL, "priority", "age"), true);
            jobDepth.register(rows(JOB_DEPTH_SQL, "status", "n"), true);
            jobOldestQueued.register(rows(JOB_OLDEST_SQL, "priority", "age"), true);
        } catch (RuntimeException ignored) { }
    }

    private List<MultiGauge.Row<Number>> rows(String sql, String tagName, String valueName) {
        return jdbc.queryForList(sql).stream()
                .map(row -> MultiGauge.Row.of(
                        Tags.of(tagName, String.valueOf(row.get(tagName))), number(row.get(valueName))))
                .toList();
    }

    private Number scalar(String sql) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(sql);
        return rows.isEmpty() ? 0 : number(rows.getFirst().get("age"));
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }
}
