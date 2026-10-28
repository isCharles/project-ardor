ALTER TABLE agent_trace_spans ADD COLUMN IF NOT EXISTS cache_creation_input_tokens INTEGER;
ALTER TABLE agent_trace_spans ADD COLUMN IF NOT EXISTS cache_read_input_tokens INTEGER;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS cache_creation_input_tokens BIGINT;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS cache_read_input_tokens BIGINT;

-- Rollback:
-- ALTER TABLE agent_runs DROP COLUMN IF EXISTS cache_read_input_tokens;
-- ALTER TABLE agent_runs DROP COLUMN IF EXISTS cache_creation_input_tokens;
-- ALTER TABLE agent_trace_spans DROP COLUMN IF EXISTS cache_read_input_tokens;
-- ALTER TABLE agent_trace_spans DROP COLUMN IF EXISTS cache_creation_input_tokens;
