-- P0-B: durable, owner-scoped Agent trace spans and run aggregates.
-- Additive only. Raw prompts, messages, tool arguments and results are never stored here;
-- spans keep model metadata, token counts, digests/lengths and bounded attributes.

CREATE TABLE agent_trace_spans (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    run_id UUID NOT NULL,
    parent_span_id UUID,
    kind VARCHAR(24) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    model VARCHAR(160),
    prompt_version VARCHAR(64),
    input_tokens INTEGER,
    output_tokens INTEGER,
    error_code VARCHAR(80),
    attributes JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_trace_kind CHECK
        (kind IN ('RUN', 'LLM', 'TOOL', 'CONTEXT', 'RETRY', 'ASYNC')),
    CONSTRAINT ck_agent_trace_status CHECK
        (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'NOT_EXECUTED', 'UNKNOWN'))
);
CREATE INDEX ix_agent_trace_owner_run
    ON agent_trace_spans (user_id, run_id, started_at);
CREATE INDEX ix_agent_trace_owner_started
    ON agent_trace_spans (user_id, started_at);

ALTER TABLE agent_runs ADD COLUMN llm_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN tool_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN loop_rounds INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN input_tokens BIGINT;
ALTER TABLE agent_runs ADD COLUMN output_tokens BIGINT;
ALTER TABLE agent_runs ADD COLUMN trace_model VARCHAR(160);
ALTER TABLE agent_runs ADD COLUMN prompt_version VARCHAR(64);

COMMENT ON TABLE agent_trace_spans IS
    'Additive execution trace for an Agent run. Attributes are bounded metadata, never raw prompts or tool payloads.';
COMMENT ON COLUMN agent_trace_spans.parent_span_id IS
    'Parent span. RUN is the root; LLM/TOOL/CONTEXT are children. No ownership is inferred from this column.';

-- Rollback (only during this migration's deployment window):
-- DROP INDEX IF EXISTS ix_agent_trace_owner_started;
-- DROP INDEX IF EXISTS ix_agent_trace_owner_run;
-- DROP TABLE IF EXISTS agent_trace_spans;
-- ALTER TABLE agent_runs DROP COLUMN IF EXISTS prompt_version;
-- ALTER TABLE agent_runs DROP COLUMN IF EXISTS trace_model;
-- ALTER TABLE agent_runs DROP COLUMN IF EXISTS output_tokens;
-- ALTER TABLE agent_runs DROP COLUMN IF EXISTS input_tokens;
-- ALTER TABLE agent_runs DROP COLUMN IF EXISTS loop_rounds;
-- ALTER TABLE agent_runs DROP COLUMN IF EXISTS tool_calls;
-- ALTER TABLE agent_runs DROP COLUMN IF EXISTS llm_calls;
