-- The plan and its steps are part of the same execution record as the tool calls they authorise.
ALTER TABLE agent_trace_spans DROP CONSTRAINT ck_agent_trace_kind;
ALTER TABLE agent_trace_spans ADD CONSTRAINT ck_agent_trace_kind CHECK (
    kind IN ('RUN', 'LLM', 'TOOL', 'CONTEXT', 'COMPACTION', 'PLAN', 'PLAN_STEP', 'THOUGHT', 'RETRY', 'ASYNC')
);

COMMENT ON COLUMN agent_trace_spans.kind IS
    'Span kind: RUN/LLM/TOOL/CONTEXT/COMPACTION/PLAN/PLAN_STEP/THOUGHT/RETRY/ASYNC. COMPACTION is a summarised-history model call; PLAN and PLAN_STEP record the accepted plan and how each step was verified. THOUGHT is a phase summary span, never raw chain-of-thought.';
