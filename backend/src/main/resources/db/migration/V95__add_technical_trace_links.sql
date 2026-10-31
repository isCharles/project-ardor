-- Technical tracing identifiers. The product trace and run state stay authoritative;
-- these columns only join a business row to the exported OpenTelemetry trace.
ALTER TABLE agent_runs ADD COLUMN otel_trace_id CHAR(32);
ALTER TABLE agent_trace_spans ADD COLUMN otel_trace_id CHAR(32);
ALTER TABLE agent_trace_spans ADD COLUMN otel_span_id CHAR(16);

-- Durable queues carry the W3C traceparent of the request that enqueued the work so
-- a worker (including one that starts after a restart) continues the same trace.
ALTER TABLE ai_jobs ADD COLUMN traceparent VARCHAR(64);
ALTER TABLE agent_message_queue ADD COLUMN traceparent VARCHAR(64);

CREATE INDEX ix_agent_runs_otel_trace ON agent_runs(otel_trace_id)
    WHERE otel_trace_id IS NOT NULL;
CREATE INDEX ix_agent_trace_spans_otel_trace ON agent_trace_spans(otel_trace_id)
    WHERE otel_trace_id IS NOT NULL;
