package com.projectardor.assistant.execution.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * Owner-scoped persistence for Agent trace spans.
 *
 * <p>This store intentionally keeps only bounded metadata: model name, token counts, status,
 * durations and small attributes. Raw prompts, messages, tool arguments and tool results must
 * never be passed here. A trace write failure is counted and logged but never changes the
 * business result of a run.</p>
 */
@Service
public class AgentTraceStore {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceStore.class);
    private static final int MAX_ATTRIBUTE_CHARS = 4_000;
    private static final int MAX_SPANS = 128;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final io.micrometer.core.instrument.Counter writeFailures;
    private final boolean enabled;
    private final int retentionDays;

    public AgentTraceStore(JdbcTemplate jdbc, ObjectMapper mapper,
            io.micrometer.core.instrument.MeterRegistry meterRegistry,
            @Value("${app.agent.trace.enabled:true}") boolean enabled,
            @Value("${app.agent.trace.retention-days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
        this.writeFailures = meterRegistry == null ? null
                : io.micrometer.core.instrument.Counter.builder("ardor.agent.trace.write.failures")
                        .description("Trace writes that were dropped without failing the Agent run")
                        .register(meterRegistry);
    }

    public boolean enabled() {
        return enabled;
    }

    public UUID startSpan(UUID userId, UUID runId, UUID parentSpanId, String kind, String name,
            Map<String, Object> attributes) {
        if (!enabled || userId == null || runId == null) return null;
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO agent_trace_spans(id, user_id, run_id, parent_span_id, kind, name, status,
                        started_at, attributes)
                    VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP, CAST(? AS jsonb))
                    """, id, userId, runId, parentSpanId, trim(kind, 24), trim(name, 160),
                    attributes(attributes));
            return id;
        } catch (RuntimeException failure) {
            recordFailure("start", runId, failure);
            return null;
        }
    }

    public void finishSpan(UUID userId, UUID id, String status, String errorCode, String model,
            String promptVersion, Integer inputTokens, Integer outputTokens, Map<String, Object> extraAttributes) {
        if (!enabled || userId == null || id == null) return;
        try {
            jdbc.update("""
                    UPDATE agent_trace_spans
                    SET status = ?, error_code = ?, model = ?, prompt_version = ?,
                        input_tokens = ?, output_tokens = ?,
                        finished_at = CURRENT_TIMESTAMP,
                        duration_ms = GREATEST(1, (EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)) * 1000)::bigint),
                        attributes = COALESCE(attributes, '{}'::jsonb) || CAST(? AS jsonb),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND user_id = ?
                    """, trim(status, 16), trim(errorCode, 80), trim(model, 160), trim(promptVersion, 64),
                    inputTokens, outputTokens, attributes(extraAttributes), id, userId);
        } catch (RuntimeException failure) {
            recordFailure("finish", id, failure);
        }
    }

    public List<Span> find(UUID userId, UUID runId) {
        if (userId == null || runId == null) return List.of();
        return jdbc.query("""
                SELECT id, run_id, parent_span_id, kind, name, status, started_at, finished_at,
                       COALESCE(duration_ms, CASE WHEN finished_at IS NULL THEN NULL
                           ELSE GREATEST(1, (EXTRACT(EPOCH FROM (finished_at - started_at)) * 1000)::bigint) END) AS duration_ms,
                       model, prompt_version, input_tokens, output_tokens, error_code, attributes::text AS attributes
                FROM agent_trace_spans
                WHERE user_id = ? AND run_id = ?
                ORDER BY started_at, created_at, id
                LIMIT ?
                """, (rs, row) -> new Span(
                        rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getObject("parent_span_id", UUID.class), rs.getString("kind"), rs.getString("name"),
                        rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        (Long) rs.getObject("duration_ms"), rs.getString("model"), rs.getString("prompt_version"),
                        (Integer) rs.getObject("input_tokens"), (Integer) rs.getObject("output_tokens"),
                        rs.getString("error_code"), jsonMap(rs.getString("attributes"))),
                userId, runId, MAX_SPANS);
    }

    public void updateRunAggregates(UUID userId, UUID runId, int llmCalls, int toolCalls, int loopRounds,
            long inputTokens, long outputTokens, String model, String promptVersion) {
        if (!enabled || userId == null || runId == null) return;
        try {
            jdbc.update("""
                    UPDATE agent_runs
                    SET llm_calls = ?, tool_calls = ?, loop_rounds = ?,
                        input_tokens = ?, output_tokens = ?, trace_model = ?, prompt_version = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND user_id = ?
                    """, llmCalls, toolCalls, loopRounds, inputTokens, outputTokens,
                    trim(model, 160), trim(promptVersion, 64), runId, userId);
        } catch (RuntimeException failure) {
            recordFailure("aggregate", runId, failure);
        }
    }

    @Scheduled(fixedDelayString = "${app.agent.trace.cleanup-delay:6h}", initialDelay = 3_600_000)
    public void cleanup() {
        if (!enabled) return;
        try {
            jdbc.update("DELETE FROM agent_trace_spans WHERE started_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')",
                    retentionDays);
        } catch (RuntimeException failure) {
            recordFailure("cleanup", null, failure);
        }
    }

    private String attributes(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return "{}";
        try {
            String json = mapper.writeValueAsString(new LinkedHashMap<>(values));
            if (json.length() <= MAX_ATTRIBUTE_CHARS) return json;
            return mapper.writeValueAsString(Map.of("truncated", true, "size", json.length()));
        } catch (RuntimeException failure) {
            return "{}";
        }
    }

    private Map<String, Object> jsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException failure) {
            return Map.of("unreadable", true);
        }
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void recordFailure(String operation, UUID key, RuntimeException failure) {
        if (writeFailures != null) writeFailures.increment();
        log.warn("Agent trace write dropped: operation={}, key={}, failure={}",
                operation, key, failure.getClass().getSimpleName());
    }

    public record Span(UUID id, UUID runId, UUID parentSpanId, String kind, String name, String status,
            Instant startedAt, Instant finishedAt, Long durationMs, String model, String promptVersion,
            Integer inputTokens, Integer outputTokens, String errorCode, Map<String, Object> attributes) {
    }
}
