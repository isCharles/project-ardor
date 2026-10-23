-- P1 graph runtime persists phase spans separately from LLM/tool work.
-- Additive constraint change only; no data rewrite and no new columns.
ALTER TABLE agent_trace_spans DROP CONSTRAINT ck_agent_trace_kind;
ALTER TABLE agent_trace_spans ADD CONSTRAINT ck_agent_trace_kind CHECK
    (kind IN ('RUN', 'LLM', 'TOOL', 'CONTEXT', 'THOUGHT', 'RETRY', 'ASYNC'));

COMMENT ON COLUMN agent_trace_spans.kind IS
    'Span kind: RUN/LLM/TOOL/CONTEXT/THOUGHT/RETRY/ASYNC. THOUGHT is a phase summary span, never raw chain-of-thought.';
